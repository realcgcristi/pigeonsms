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

/**
 * End-to-end encryption for DMs. **Ships behind the OFF `e2ee` flag and is
 * EXPERIMENTAL** — correctness over completeness. The server only ever stores
 * ciphertext (encrypted=1) and never decrypts; FTS + push previews skip encrypted
 * messages (enforced server-side).
 *
 * Pipeline:
 *  1. [publishDevice] registers this device's X25519 identity pub key (private key
 *     wrapped at rest by the Android Keystore — see [IdentityKeyStore]).
 *  2. For a DM, the initiator mints a random per-channel key, [wrapDmKeyFor]s it to
 *     every recipient device pub key via libsodium sealed box, and POSTs the
 *     envelopes. Recipients [unwrapDmKey] from their own envelope.
 *  3. Both sides seed a symmetric [Ratchet] from the shared DM key and
 *     [encrypt]/[decrypt] the message stream. Ratchet state persists per channel
 *     through core/db's [app.pigeonsms.db.RatchetStateDao] (via [RatchetStateStore]).
 *  4. [buildKeyBackup]/[restoreKeyBackup] wrap the identity secret under an
 *     Argon2id-derived key for multi-device recovery.
 *
 * TODO(e2ee): end-to-end real-device testing of the whole flow. Notably:
 *   - group (>2 device) DMs: wrapDmKeyFor already fans out, but ratchet init picks
 *     initiator/responder by a 2-party assumption. Multi-party needs sender keys.
 *   - envelope de-dup / rotation when a peer adds a device mid-conversation.
 *   - the DH ratchet step (post-compromise security) — see [Ratchet] TODO.
 */
interface E2eeManager {
    /** This device's X25519 identity keypair (public + wrapped-at-rest secret). */
    suspend fun deviceKeyPair(): Sodium.KeyPairBytes

    /** Register/refresh this device on the server; returns the server device id. */
    suspend fun publishDevice(name: String? = null): String

    /**
     * Wrap this channel's per-DM key (minting one if absent) to every recipient
     * device pub key via sealed box. Returns envelopes ready for POST — the caller
     * (repository layer) is responsible for [PigeonApi.postKeyEnvelopes].
     */
    suspend fun wrapDmKeyFor(channelId: String, devicePubKeys: List<DevicePub>): List<KeyEnvelopeDto>

    /**
     * Resolve this channel's per-DM key from an envelope addressed to one of our
     * devices, persist it, and seed the ratchet (as responder). Idempotent.
     */
    suspend fun unwrapDmKey(channelId: String, wrappedKeyBase64: String)

    /** Encrypt [plaintext] for [channelId]; returns base64 to place in message content. */
    suspend fun encrypt(channelId: String, plaintext: String): String

    /** Decrypt base64 [ciphertext] for [channelId]; returns the plaintext string. */
    suspend fun decrypt(channelId: String, ciphertext: String): String

    /** Build the {blob, kdf_salt, kdf_params} backup of this device's identity secret. */
    suspend fun buildKeyBackup(password: String): KeyBackupBlob

    /** Restore + install the identity secret from a backup; re-publishes the device. */
    suspend fun restoreKeyBackup(password: String, blob: KeyBackupBlob)
}

/** A recipient device: its server id (envelope target) + base64 X25519 pub key. */
data class DevicePub(val deviceId: String, val pubKeyBase64: String)

/** Transport shape of a key backup — mirrors [app.pigeonsms.network.KeyBackupDto]. */
data class KeyBackupBlob(val blob: String, val kdfSalt: String, val kdfParams: String)

/**
 * Default implementation. Construct via [create] so callers don't wire the internal
 * Keystore / DAO-backed stores by hand.
 */
class DefaultE2eeManager internal constructor(
    private val api: PigeonApi,
    private val identity: IdentityKeyStore,
    private val ratchets: RatchetStateStore,
) : E2eeManager {

    // Guards the per-channel load→mutate→save ratchet cycle (DataStore/DAO writes
    // aren't atomic across a read-modify-write; the ratchet MUST advance serially).
    private val locks = HashMap<String, Mutex>()
    @Synchronized private fun lockFor(channelId: String) = locks.getOrPut(channelId) { Mutex() }

    override suspend fun deviceKeyPair(): Sodium.KeyPairBytes = withContext(Dispatchers.IO) {
        identity.getOrCreate()
    }

    override suspend fun publishDevice(name: String?): String = withContext(Dispatchers.IO) {
        val kp = identity.getOrCreate()
        val id = api.postDevice(Sodium.b64(kp.publicKey), name)
        if (id.isNotEmpty()) identity.setDeviceId(id)
        id
    }

    override suspend fun wrapDmKeyFor(
        channelId: String,
        devicePubKeys: List<DevicePub>,
    ): List<KeyEnvelopeDto> = withContext(Dispatchers.IO) {
        val dmKey = ratchets.loadDmKey(channelId) ?: Sodium.randomBytes(Sodium.AEAD_KEYBYTES).also {
            ratchets.saveDmKey(channelId, it)
            // Whoever mints the key is the ratchet initiator.
            ratchets.setInitiator(channelId, true)
            ensureRatchet(channelId, it, initiator = true)
        }
        devicePubKeys.map { d ->
            val sealed = Sodium.seal(dmKey, Sodium.unb64(d.pubKeyBase64))
            // id/from_user are server-assigned on POST; empty here per the API's
            // postKeyEnvelopes body (it only reads to_device + wrapped_key).
            KeyEnvelopeDto(
                id = "",
                to_device = d.deviceId,
                from_user = "",
                wrapped_key = Sodium.b64(sealed),
            )
        }
    }

    override suspend fun unwrapDmKey(channelId: String, wrappedKeyBase64: String) =
        withContext(Dispatchers.IO) {
            if (ratchets.loadDmKey(channelId) != null) return@withContext // already resolved
            val kp = identity.getOrCreate()
            val dmKey = Sodium.sealOpen(Sodium.unb64(wrappedKeyBase64), kp.publicKey, kp.secretKey)
            ratchets.saveDmKey(channelId, dmKey)
            ratchets.setInitiator(channelId, false)
            ensureRatchet(channelId, dmKey, initiator = false)
        }

    override suspend fun encrypt(channelId: String, plaintext: String): String =
        withContext(Dispatchers.IO) {
            lockFor(channelId).withLock {
                val ratchet = Ratchet(requireRatchetState(channelId))
                val ct = ratchet.encrypt(plaintext.toByteArray(Charsets.UTF_8))
                ratchets.save(ratchet.snapshot)
                Sodium.b64(ct)
            }
        }

    override suspend fun decrypt(channelId: String, ciphertext: String): String =
        withContext(Dispatchers.IO) {
            lockFor(channelId).withLock {
                val ratchet = Ratchet(requireRatchetState(channelId))
                val pt = ratchet.decrypt(Sodium.unb64(ciphertext))
                ratchets.save(ratchet.snapshot)
                String(pt, Charsets.UTF_8)
            }
        }

    override suspend fun buildKeyBackup(password: String): KeyBackupBlob =
        withContext(Dispatchers.IO) {
            val kp = identity.getOrCreate()
            // Bundle = versioned JSON of the identity secret. Extendable (per-channel
            // DM keys could be added later) without breaking older restores.
            val bundle = JSONObject()
                .put("v", 1)
                .put("identitySecret", Sodium.b64(kp.secretKey))
                .toString()
                .toByteArray(Charsets.UTF_8)
            val sealed = KeyBackup.seal(password, bundle)
            KeyBackupBlob(sealed.ciphertext, sealed.salt, sealed.params)
        }

    override suspend fun restoreKeyBackup(password: String, blob: KeyBackupBlob) =
        withContext(Dispatchers.IO) {
            val bundle = KeyBackup.open(password, KeyBackup.Blob(blob.blob, blob.kdfSalt, blob.kdfParams))
            val o = JSONObject(String(bundle, Charsets.UTF_8))
            val secret = Sodium.unb64(o.getString("identitySecret"))
            identity.importSecret(secret)
            // The restored identity is a NEW device server-side (envelopes are keyed
            // to a device id); re-publish so peers can wrap to it.
            val kp = identity.getOrCreate()
            val id = api.postDevice(Sodium.b64(kp.publicKey), null)
            if (id.isNotEmpty()) identity.setDeviceId(id)
        }

    // --- ratchet lifecycle helpers ---

    /** Seed + persist a ratchet for a channel if one isn't stored yet. */
    private suspend fun ensureRatchet(channelId: String, dmKey: ByteArray, initiator: Boolean) {
        if (ratchets.load(channelId) != null) return
        val r = Ratchet.fromSharedKey(channelId, dmKey, initiator)
        ratchets.save(r.snapshot)
    }

    /**
     * Load the ratchet state, lazily seeding it from a stored DM key if the ratchet
     * row is missing (e.g. key delivered but stream not yet started). Fails loudly
     * if there's no key material at all — callers must gate on the e2ee flag +
     * key-exchange completion before encrypt/decrypt.
     */
    private suspend fun requireRatchetState(channelId: String): RatchetState {
        ratchets.load(channelId)?.let { return it }
        val dmKey = ratchets.loadDmKey(channelId)
            ?: error("no e2ee key material for channel $channelId (key exchange not done)")
        val initiator = ratchets.isInitiator(channelId) ?: false
        val r = Ratchet.fromSharedKey(channelId, dmKey, initiator)
        ratchets.save(r.snapshot)
        return r.snapshot
    }

    companion object {
        /**
         * Wire up the manager against the app database + API. E2EE ships flag-OFF;
         * only construct/use this when the `e2ee` flag is enabled.
         *
         * TODO(e2ee): the `e2ee` client flag belongs on ThemePrefs (Stores.kt, owned
         * by another agent this cycle) — default FALSE. Gate all callers on it.
         */
        fun create(context: Context, api: PigeonApi, db: PigeonDatabase): E2eeManager =
            DefaultE2eeManager(
                api = api,
                identity = IdentityKeyStore(context.applicationContext),
                ratchets = DaoRatchetStateStore(db.ratchets()),
            )
    }
}
