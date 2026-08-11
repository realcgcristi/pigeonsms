package app.pigeonsms.data.e2ee

import android.content.Context
import android.net.Uri
import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.KeyEnvelopeDto
import app.pigeonsms.network.PigeonApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface E2eeManager {
    suspend fun deviceKeyPair(): Sodium.KeyPairBytes
    suspend fun deviceId(): String?
    suspend fun publishDevice(name: String? = null): String
    suspend fun hasSession(channelId: String): Boolean
    suspend fun wrapDmKeyFor(channelId: String, devicePubKeys: List<DevicePub>): List<KeyEnvelopeDto>
    suspend fun unwrapDmKey(channelId: String, wrappedKeyBase64: String)
    suspend fun encrypt(channelId: String, plaintext: String, attachment: AttachmentSecret? = null): String
    suspend fun decrypt(channelId: String, ciphertext: String, authorId: String): ProtectedMessage
    suspend fun protectAttachment(bytes: ByteArray, name: String, type: String): ProtectedAttachment
    suspend fun protectAttachmentStream(
        openStream: () -> java.io.InputStream,
        name: String,
        type: String,
        size: Long,
    ): ProtectedAttachmentFile
    suspend fun decryptAttachmentToCache(cacheKey: String, ciphertext: ByteArray, secret: AttachmentSecret): String
    suspend fun syncPendingDevices(): Int
    suspend fun safetyNumber(ownerId: String, peerId: String): SafetyFingerprint
    suspend fun buildKeyBackup(password: String): KeyBackupBlob
    suspend fun restoreKeyBackup(password: String, blob: KeyBackupBlob)
}

data class DevicePub(val deviceId: String, val pubKeyBase64: String)
data class KeyBackupBlob(val blob: String, val kdfSalt: String, val kdfParams: String)
data class AttachmentSecret(
    val v: Int = 1,
    val k: String,
    val i: String,
    val n: String,
    val t: String,
    val z: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", v)
        .put("k", k)
        .put("i", i)
        .put("n", n)
        .put("t", t)
        .put("z", z)

    companion object {
        fun fromJson(value: JSONObject): AttachmentSecret? = runCatching {
            AttachmentSecret(
                v = value.getInt("v"),
                k = value.getString("k"),
                i = value.getString("i"),
                n = value.getString("n"),
                t = value.getString("t"),
                z = value.getLong("z"),
            ).takeIf { it.v == 1 && Sodium.unb64(it.k).size == 32 && Sodium.unb64(it.i).size == 12 }
        }.getOrNull()

        fun fromMetadata(value: String?): AttachmentSecret? = runCatching {
            fromJson(JSONObject(value ?: return null).getJSONObject("e2ee_attachment"))
        }.getOrNull()
    }
}
data class ProtectedMessage(val text: String, val attachment: AttachmentSecret? = null)
data class ProtectedAttachment(val bytes: ByteArray, val secret: AttachmentSecret)
data class ProtectedAttachmentFile(val file: File, val secret: AttachmentSecret)
data class SafetyFingerprint(val number: String, val qr: String)

class DefaultE2eeManager internal constructor(
    private val context: Context,
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

    override suspend fun deviceId(): String? = withContext(Dispatchers.IO) {
        identity.deviceId()
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

    override suspend fun encrypt(
        channelId: String,
        plaintext: String,
        attachment: AttachmentSecret?,
    ): String = withContext(Dispatchers.IO) {
        lockFor(channelId).withLock {
            val master = ratchets.loadMaster(channelId) ?: error("encrypted session is not ready")
            val ownId = identity.deviceId() ?: error("this encryption device is not registered")
            val pair = identity.getOrCreate()
            val protected = JSONObject().put("v", 1).put("text", plaintext)
            if (attachment != null) protected.put("attachment", attachment.toJson())
            val payload = protected.toString()
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

    override suspend fun decrypt(
        channelId: String,
        ciphertext: String,
        authorId: String,
    ): ProtectedMessage = withContext(Dispatchers.IO) {
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
            ProtectedMessage(
                text = protectedPayload.getString("text"),
                attachment = protectedPayload.optJSONObject("attachment")?.let { AttachmentSecret.fromJson(it) },
            )
        }
    }

    override suspend fun protectAttachment(
        bytes: ByteArray,
        name: String,
        type: String,
    ): ProtectedAttachment = withContext(Dispatchers.IO) {
        val key = Sodium.randomBytes(32)
        val mediaType = type.ifBlank { "application/octet-stream" }
        val ad = attachmentContext(name, mediaType, bytes.size.toLong())
        val sealed = Sodium.aesGcmSeal(key, bytes, ad)
        ProtectedAttachment(
            bytes = sealed.ciphertext,
            secret = AttachmentSecret(
                k = Sodium.b64(key),
                i = Sodium.b64(sealed.iv),
                n = name,
                t = mediaType,
                z = bytes.size.toLong(),
            ),
        )
    }

    override suspend fun protectAttachmentStream(
        openStream: () -> java.io.InputStream,
        name: String,
        type: String,
        size: Long,
    ): ProtectedAttachmentFile = withContext(Dispatchers.IO) {
        require(size >= 0) { "attachment size is required" }
        val key = Sodium.randomBytes(32)
        val iv = Sodium.randomBytes(12)
        val mediaType = type.ifBlank { "application/octet-stream" }
        val directory = File(context.cacheDir, "e2ee-upload").apply { mkdirs() }
        val output = File.createTempFile("pigeon-", ".pigeon", directory)
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(attachmentContext(name, mediaType, size))
            openStream().use { input ->
                CipherOutputStream(FileOutputStream(output), cipher).use { encrypted -> input.copyTo(encrypted) }
            }
        }.onFailure {
            output.delete()
        }.getOrThrow()
        ProtectedAttachmentFile(
            file = output,
            secret = AttachmentSecret(
                k = Sodium.b64(key),
                i = Sodium.b64(iv),
                n = name,
                t = mediaType,
                z = size,
            ),
        )
    }

    override suspend fun decryptAttachmentToCache(
        cacheKey: String,
        ciphertext: ByteArray,
        secret: AttachmentSecret,
    ): String = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "e2ee-media").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$cacheKey:${secret.k}:${secret.i}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val suffix = secret.n.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?.let { ".$it" }
            .orEmpty()
        val target = File(directory, "$digest$suffix")
        if (!target.exists()) {
            val plaintext = Sodium.aesGcmOpen(
                Sodium.unb64(secret.k),
                Sodium.unb64(secret.i),
                ciphertext,
                attachmentContext(secret.n, secret.t, secret.z),
            )
            val pending = File(directory, "$digest.pending")
            pending.writeBytes(plaintext)
            if (!pending.renameTo(target)) {
                target.writeBytes(plaintext)
                pending.delete()
            }
        }
        Uri.fromFile(target).toString()
    }

    override suspend fun syncPendingDevices(): Int = withContext(Dispatchers.IO) {
        val ownId = identity.deviceId() ?: return@withContext 0
        val pending = api.pendingDeviceSync().filter { it.id != ownId && it.pub_key.isNotBlank() }
        val masters = ratchets.listMasters()
        var completed = 0
        pending.forEach { device ->
            masters.forEach { (channelId, _) ->
                val envelopes = wrapDmKeyFor(channelId, listOf(DevicePub(device.id, device.pub_key)))
                    .filter { it.to_device == device.id }
                if (envelopes.isNotEmpty()) api.postKeyEnvelopes(channelId, envelopes)
            }
            api.completeDeviceSync(device.id)
            completed += 1
        }
        completed
    }

    override suspend fun safetyNumber(ownerId: String, peerId: String): SafetyFingerprint = withContext(Dispatchers.IO) {
        val own = api.myDevices().map { "$ownerId:${it.id}:${it.pub_key}" }
        val peer = api.userDevices(peerId).map { "$peerId:${it.id}:${it.pub_key}" }
        val number = fingerprint(own + peer)
        SafetyFingerprint(number, "pigeonsms-safety://v1/${listOf(ownerId, peerId).sorted().joinToString("/")}/$number")
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

    private fun attachmentContext(name: String, type: String, size: Long) =
        "open-pigeon-attachment-v1:$name:$type:$size".toByteArray(Charsets.UTF_8)

    private fun fingerprint(values: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val canonical = values.sorted().joinToString("\n")
        val first = hash.digest("open-pigeon-safety-v1\n$canonical".toByteArray(Charsets.UTF_8))
        val second = hash.digest("open-pigeon-safety-v1-expand\n".toByteArray(Charsets.UTF_8) + first)
        val modulo = BigInteger.valueOf(100_000)
        return (first + second).asList().chunked(5).joinToString("") { chunk ->
            BigInteger(1, chunk.toByteArray()).mod(modulo).toString().padStart(5, '0')
        }.take(60)
    }

    companion object {
        fun create(context: Context, api: PigeonApi, db: PigeonDatabase): E2eeManager =
            DefaultE2eeManager(
                context = context.applicationContext,
                api = api,
                identity = IdentityKeyStore(context.applicationContext),
                ratchets = DaoRatchetStateStore(db.ratchets()),
            )
    }
}
