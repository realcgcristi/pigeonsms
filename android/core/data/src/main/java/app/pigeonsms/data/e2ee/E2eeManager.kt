package app.pigeonsms.data.e2ee

import android.content.Context
import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.KeyEnvelopeDto
import app.pigeonsms.network.PigeonApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

interface E2eeManager {
    suspend fun deviceKeyPair(): Sodium.KeyPairBytes
    suspend fun publishDevice(name: String? = null): String
    suspend fun hasSession(channelId: String): Boolean
    suspend fun wrapDmKeyFor(channelId: String, devicePubKeys: List<DevicePub>): List<KeyEnvelopeDto>
    suspend fun unwrapDmKey(channelId: String, wrappedKeyBase64: String)
    suspend fun encrypt(channelId: String, plaintext: String): String
    suspend fun decrypt(channelId: String, ciphertext: String, authorId: String): String
    suspend fun buildKeyBackup(password: String): KeyBackupBlob
    suspend fun restoreKeyBackup(password: String, blob: KeyBackupBlob)
}

data class DevicePub(val deviceId: String, val pubKeyBase64: String)
data class KeyBackupBlob(val blob: String, val kdfSalt: String, val kdfParams: String)

class DefaultE2eeManager internal constructor(
    private val api: PigeonApi,
    private val identity: IdentityKeyStore,
    private val ratchets: RatchetStateStore,
) : E2eeManager {
    private val locks = HashMap<String, Mutex>()

    @Synchronized
    private fun lockFor(channelId: String) = locks.getOrPut(channelId) { Mutex() }

    override suspend fun deviceKeyPair(): Sodium.KeyPairBytes = withContext(Dispatchers.IO) {
        identity.getOrCreate()
    }

    override suspend fun publishDevice(name: String?): String = withContext(Dispatchers.IO) {
        val pair = identity.getOrCreate()
        val id = api.postDevice(Sodium.b64(pair.publicKey), name)
        if (id.isNotEmpty()) identity.setDeviceId(id)
        id
    }

    override suspend fun hasSession(channelId: String): Boolean = withContext(Dispatchers.IO) {
        val master = ratchets.loadMaster(channelId)
        master != null && master.devices.isNotEmpty() && identity.deviceId() != null
    }

    override suspend fun wrapDmKeyFor(
        channelId: String,
        devicePubKeys: List<DevicePub>,
    ): List<KeyEnvelopeDto> = withContext(Dispatchers.IO) {
        lockFor(channelId).withLock {
            val existing = ratchets.loadMaster(channelId)
            val devices = ((existing?.devices ?: emptyList()) + devicePubKeys)
                .filter { it.deviceId.isNotBlank() && it.pubKeyBase64.isNotBlank() }
                .distinctBy { it.deviceId }
            val master = existing?.copy(devices = devices) ?: ChannelMaster(
                keyId = UUID.randomUUID().toString(),
                key = Sodium.randomBytes(32),
                devices = devices,
            )
            ratchets.saveMaster(channelId, master)
            devices.map { device ->
                KeyEnvelopeDto(
                    id = "",
                    key_id = master.keyId,
                    to_device = device.deviceId,
                    from_user = "",
                    wrapped_key = wrapMaster(channelId, master, device),
                )
            }
        }
    }

    override suspend fun unwrapDmKey(channelId: String, wrappedKeyBase64: String) = withContext(Dispatchers.IO) {
        lockFor(channelId).withLock {
            val deviceId = identity.deviceId() ?: error("this encryption device is not registered")
            val opened = openMaster(channelId, deviceId, wrappedKeyBase64, identity.getOrCreate())
            val current = ratchets.loadMaster(channelId)
            ratchets.saveMaster(channelId, opened.copy(devices = current?.devices ?: emptyList()))
        }
    }

    override suspend fun encrypt(channelId: String, plaintext: String): String = withContext(Dispatchers.IO) {
        lockFor(channelId).withLock {
            val master = ratchets.loadMaster(channelId) ?: error("encrypted session is not ready")
            val ownId = identity.deviceId() ?: error("this encryption device is not registered")
            val pair = identity.getOrCreate()
            val payload = JSONObject().put("v", 1).put("text", plaintext).toString()
            val entries = JSONObject()
            master.devices.forEach { device ->
                if (device.deviceId == ownId) {
                    val key = Ratchet.hkdf(master.key, ByteArray(32), "open-pigeon-local-copy-v1:$ownId", 32)
                    val ad = "open-pigeon-local-copy-v1:$ownId".toByteArray(Charsets.UTF_8)
                    val sealed = Sodium.aesGcmSeal(key, payload.toByteArray(Charsets.UTF_8), ad)
                    entries.put(
                        device.deviceId,
                        JSONObject().put("l", 1).put("i", Sodium.b64(sealed.iv)).put("c", Sodium.b64(sealed.ciphertext)),
                    )
                } else {
                    val remoteKey = Sodium.unb64(device.pubKeyBase64)
                    val stored = ratchets.load(channelId, device.deviceId)
                    val ratchet = if (stored != null && stored.remoteIdentity.contentEquals(remoteKey)) {
                        Ratchet(stored)
                    } else {
                        Ratchet.initialize(channelId, master.key, ownId, device.deviceId, pair, remoteKey)
                    }
                    val packet = ratchet.encrypt(payload)
                    ratchets.save(ratchet.snapshot)
                    entries.put(device.deviceId, packetJson(packet))
                }
            }
            require(entries.length() > 0) { "no encrypted recipient devices are available" }
            JSONObject()
                .put("v", 1)
                .put("k", master.keyId)
                .put("s", ownId)
                .put("e", entries)
                .toString()
        }
    }

    override suspend fun decrypt(channelId: String, ciphertext: String, authorId: String): String = withContext(Dispatchers.IO) {
        lockFor(channelId).withLock {
            val wire = JSONObject(ciphertext)
            require(wire.getInt("v") == 1) { "unsupported encrypted message" }
            val master = ratchets.loadMaster(channelId) ?: error("encrypted session is not ready")
            require(wire.getString("k") == master.keyId) { "the conversation encryption key changed" }
            val ownId = identity.deviceId() ?: error("this encryption device is not registered")
            val entry = wire.getJSONObject("e").optJSONObject(ownId)
                ?: error("this message was not encrypted to this device")
            val payload = if (entry.optInt("l") == 1) {
                val key = Ratchet.hkdf(master.key, ByteArray(32), "open-pigeon-local-copy-v1:$ownId", 32)
                val ad = "open-pigeon-local-copy-v1:$ownId".toByteArray(Charsets.UTF_8)
                String(
                    Sodium.aesGcmOpen(key, Sodium.unb64(entry.getString("i")), Sodium.unb64(entry.getString("c")), ad),
                    Charsets.UTF_8,
                )
            } else {
                val senderDevice = wire.getString("s")
                val remote = master.devices.firstOrNull { it.deviceId == senderDevice }
                    ?: (api.myDevices() + api.userDevices(authorId))
                        .firstOrNull { it.id == senderDevice }
                        ?.let { DevicePub(it.id, it.pub_key) }
                    ?: error("the sending device was revoked")
                val remoteKey = Sodium.unb64(remote.pubKeyBase64)
                val pair = identity.getOrCreate()
                val stored = ratchets.load(channelId, remote.deviceId)
                val ratchet = if (stored != null && stored.remoteIdentity.contentEquals(remoteKey)) {
                    Ratchet(stored)
                } else {
                    Ratchet.initialize(channelId, master.key, ownId, remote.deviceId, pair, remoteKey)
                }
                val plaintext = ratchet.decrypt(parsePacket(entry))
                ratchets.save(ratchet.snapshot)
                plaintext
            }
            val protectedPayload = JSONObject(payload)
            require(protectedPayload.getInt("v") == 1) { "invalid encrypted message" }
            protectedPayload.getString("text")
        }
    }

    override suspend fun buildKeyBackup(password: String): KeyBackupBlob = withContext(Dispatchers.IO) {
        val pair = identity.getOrCreate()
        val bundle = JSONObject()
            .put("v", 1)
            .put("identitySecret", Sodium.b64(pair.secretKey))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val sealed = KeyBackup.seal(password, bundle)
        KeyBackupBlob(sealed.ciphertext, sealed.salt, sealed.params)
    }

    override suspend fun restoreKeyBackup(password: String, blob: KeyBackupBlob) = withContext(Dispatchers.IO) {
        val bundle = KeyBackup.open(password, KeyBackup.Blob(blob.blob, blob.kdfSalt, blob.kdfParams))
        val secret = Sodium.unb64(JSONObject(String(bundle, Charsets.UTF_8)).getString("identitySecret"))
        identity.importSecret(secret)
        val pair = identity.getOrCreate()
        val id = api.postDevice(Sodium.b64(pair.publicKey), null)
        if (id.isNotEmpty()) identity.setDeviceId(id)
    }

    private fun wrapMaster(channelId: String, master: ChannelMaster, device: DevicePub): String {
        val ephemeral = Sodium.newBoxKeyPair()
        val shared = Sodium.scalarMult(ephemeral.secretKey, Sodium.unb64(device.pubKeyBase64))
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("open-pigeon-key-salt-v1:$channelId:${device.deviceId}".toByteArray(Charsets.UTF_8))
        val key = Ratchet.hkdf(shared, salt, "open-pigeon-key-envelope-v1", 32)
        val ad = "open-pigeon-key-envelope-v1:$channelId:${device.deviceId}".toByteArray(Charsets.UTF_8)
        val payload = JSONObject()
            .put("v", 1)
            .put("channelId", channelId)
            .put("keyId", master.keyId)
            .put("key", Sodium.b64(master.key))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val sealed = Sodium.aesGcmSeal(key, payload, ad)
        return "opk1.${Sodium.b64(ephemeral.publicKey)}.${Sodium.b64(sealed.iv)}.${Sodium.b64(sealed.ciphertext)}"
    }

    private fun openMaster(
        channelId: String,
        deviceId: String,
        envelope: String,
        pair: Sodium.KeyPairBytes,
    ): ChannelMaster {
        val parts = envelope.split('.')
        require(parts.size == 4 && parts[0] == "opk1") { "unsupported key envelope" }
        val shared = Sodium.scalarMult(pair.secretKey, Sodium.unb64(parts[1]))
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("open-pigeon-key-salt-v1:$channelId:$deviceId".toByteArray(Charsets.UTF_8))
        val key = Ratchet.hkdf(shared, salt, "open-pigeon-key-envelope-v1", 32)
        val ad = "open-pigeon-key-envelope-v1:$channelId:$deviceId".toByteArray(Charsets.UTF_8)
        val payload = JSONObject(
            String(Sodium.aesGcmOpen(key, Sodium.unb64(parts[2]), Sodium.unb64(parts[3]), ad), Charsets.UTF_8),
        )
        require(payload.getInt("v") == 1 && payload.getString("channelId") == channelId)
        return ChannelMaster(payload.getString("keyId"), Sodium.unb64(payload.getString("key")))
    }

    private fun packetJson(packet: RatchetPacket): JSONObject = JSONObject()
        .put(
            "h",
            JSONObject()
                .put("v", 1)
                .put("d", packet.header.d)
                .put("k", packet.header.k)
                .put("p", packet.header.p)
                .put("n", packet.header.n),
        )
        .put("i", packet.iv)
        .put("c", packet.ciphertext)

    private fun parsePacket(json: JSONObject): RatchetPacket {
        val header = json.getJSONObject("h")
        return RatchetPacket(
            RatchetHeader(
                v = header.getInt("v"),
                d = header.getString("d"),
                k = header.getString("k"),
                p = header.getLong("p"),
                n = header.getLong("n"),
            ),
            json.getString("i"),
            json.getString("c"),
        )
    }

    companion object {
        fun create(context: Context, api: PigeonApi, db: PigeonDatabase): E2eeManager =
            DefaultE2eeManager(
                api = api,
                identity = IdentityKeyStore(context.applicationContext),
                ratchets = DaoRatchetStateStore(db.ratchets()),
            )
    }
}
