package app.pigeonsms.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import app.pigeonsms.network.ApiUser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NetworklessMessage(
    val version: Int,
    val spaceId: String,
    val channelId: String,
    val nonce: String,
    val author: ApiUser,
    val content: String,
    val createdAt: Long,
)

@Serializable
private data class NetworklessWire(val iv: String, val data: String)

data class NetworklessStatus(
    val active: Boolean = false,
    val peers: Int = 0,
    val exchanged: Int = 0,
    val error: String? = null,
)

class NetworklessManager(
    context: Context,
    private val chat: ChatRepository,
) {
    private data class Config(
        val spaceId: String,
        val channelIds: Set<String>,
        val userId: String,
        val username: String,
        val avatar: String?,
        val accent: String?,
    )

    private data class Peer(val socket: Socket, val writer: BufferedWriter)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val peers = ConcurrentHashMap<String, Peer>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val _status = MutableStateFlow(NetworklessStatus())
    val status: StateFlow<NetworklessStatus> = _status
    private var sessionScope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var config: Config? = null
    private var key: SecretKeySpec? = null
    private var serviceName = ""
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    suspend fun start(
        spaceId: String,
        channelIds: Set<String>,
        passphrase: String,
        userId: String,
        username: String,
        avatar: String? = null,
        accent: String? = null,
    ) {
        require(passphrase.length >= 8) { "use at least 8 characters" }
        require(channelIds.isNotEmpty()) { "this nest has no message channels" }
        stop()
        key = withContext(Dispatchers.Default) { deriveKey(spaceId, passphrase) }
        config = Config(spaceId, channelIds, userId, username, avatar, accent)
        seen.clear()
        _status.value = NetworklessStatus(active = true)
        multicastLock = wifi.createMulticastLock("pigeonsms-networkless").apply {
            setReferenceCounted(false)
            acquire()
        }
        val scope = CoroutineScope(SupervisorJob(rootScope.coroutineContext[Job]) + Dispatchers.IO)
        sessionScope = scope
        val socket = ServerSocket(0)
        server = socket
        scope.launch {
            while (true) {
                val peer = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(peer) }
            }
        }
        scope.launch {
            chat.networklessOutbox.collect { outbound ->
                if (outbound.channelId in channelIds && !outbound.hasAttachment) send(outbound)
            }
        }
        register(spaceId, socket.localPort)
        discover(spaceId)
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        runCatching { server?.close() }
        server = null
        peers.values.forEach { runCatching { it.socket.close() } }
        peers.clear()
        connecting.clear()
        sessionScope?.cancel()
        sessionScope = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        config = null
        key = null
        _status.value = NetworklessStatus()
    }

    private fun register(spaceId: String, port: Int) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                serviceName = info.serviceName
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = fail("could not advertise nearby session")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        val info = NsdServiceInfo().apply {
            serviceName = "pigeon-${spaceId.take(8)}-${UUID.randomUUID().toString().take(6)}"
            serviceType = "_pigeonsms._tcp."
            setPort(port)
            setAttribute("space", spaceId)
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover(spaceId: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == serviceName || !connecting.add(info.serviceName)) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        connecting.remove(serviceInfo.serviceName)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val resolvedSpace = serviceInfo.attributes["space"]?.toString(Charsets.UTF_8)
                        if (resolvedSpace != spaceId || serviceInfo.serviceName == serviceName) {
                            connecting.remove(serviceInfo.serviceName)
                            return
                        }
                        sessionScope?.launch {
                            runCatching { Socket(serviceInfo.host, serviceInfo.port) }
                                .onSuccess { handle(it) }
                            connecting.remove(serviceInfo.serviceName)
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = fail("could not scan the local network")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        nsd.discoverServices("_pigeonsms._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private suspend fun handle(socket: Socket) {
        val id = socket.remoteSocketAddress.toString()
        socket.tcpNoDelay = true
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val previous = peers.put(id, Peer(socket, writer))
        previous?.let { runCatching { it.socket.close() } }
        updatePeers()
        flushPending()
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                receive(line, id)
            }
        } catch (_: Exception) {
        } finally {
            peers.remove(id)?.let { runCatching { it.socket.close() } }
            updatePeers()
        }
    }

    private suspend fun flushPending() {
        val current = config ?: return
        chat.networklessPending(current.channelIds).forEach { outbound ->
            if (!outbound.hasAttachment && outbound.nonce !in seen) send(outbound)
        }
    }

    private suspend fun send(outbound: NearbyOutbound) {
        val current = config ?: return
        if (outbound.nonce in seen || outbound.channelId !in current.channelIds) return
        val message = NetworklessMessage(
            version = 1,
            spaceId = current.spaceId,
            channelId = outbound.channelId,
            nonce = outbound.nonce,
            author = ApiUser(
                id = current.userId,
                username = current.username,
                avatar_key = current.avatar,
                accent = current.accent,
            ),
            content = outbound.content,
            createdAt = outbound.createdAt,
        )
        if (broadcast(encrypt(json.encodeToString(message)))) {
            seen.add(outbound.nonce)
            chat.markNearby(outbound.nonce)
            _status.update { it.copy(exchanged = it.exchanged + 1, error = null) }
        }
    }

    private suspend fun receive(raw: String, source: String) {
        val current = config ?: return
        val message = runCatching { json.decodeFromString<NetworklessMessage>(decrypt(raw)) }
            .getOrElse {
                fail("nearby encryption keys do not match")
                return
            }
        if (message.version != 1 || message.spaceId != current.spaceId || message.channelId !in current.channelIds || message.content.length > 8000) return
        if (!seen.add(message.nonce)) return
        chat.applyNearby(message)
        _status.update { it.copy(exchanged = it.exchanged + 1, error = null) }
        broadcast(raw, source)
    }

    private fun broadcast(raw: String, exclude: String? = null): Boolean {
        var delivered = false
        peers.forEach { (id, peer) ->
            if (id == exclude) return@forEach
            val sent = runCatching {
                synchronized(peer.writer) {
                    peer.writer.write(raw)
                    peer.writer.newLine()
                    peer.writer.flush()
                }
            }.isSuccess
            if (sent) delivered = true
        }
        return delivered
    }

    private fun encrypt(value: String): String {
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key), GCMParameterSpec(128, iv))
        return json.encodeToString(
            NetworklessWire(
                Base64.encodeToString(iv, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            )
        )
    }

    private fun decrypt(value: String): String {
        val wire = json.decodeFromString<NetworklessWire>(value)
        val iv = Base64.decode(wire.iv, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val bytes = Base64.decode(wire.data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireNotNull(key), GCMParameterSpec(128, iv))
        return cipher.doFinal(bytes).toString(Charsets.UTF_8)
    }

    private fun deriveKey(spaceId: String, passphrase: String): SecretKeySpec {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("pigeon-nearby-v1:$spaceId".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 250_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun updatePeers() {
        _status.update { it.copy(peers = peers.size, error = null) }
    }

    private fun fail(message: String) {
        _status.update { it.copy(error = message) }
    }
}
