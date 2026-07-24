package app.pigeonsms.data.e2ee

import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import org.json.JSONObject

/**
 * Password-derived encrypted key backup for multi-device recovery (EXPERIMENTAL,
 * flag-OFF). Produces the {blob, kdf_salt, kdf_params} triple stored via
 * PUT /auth/key-backup (see [KeyBackupDto]).
 *
 * KDF: Argon2id (libsodium crypto_pwhash) — memory-hard, the recommended default.
 * kdf_params is JSON so the server stays algorithm-agnostic and future backups can
 * bump cost or swap to scrypt without a schema change. The derived 32-byte key is
 * used directly as an XChaCha20-Poly1305 AEAD key over the serialized key bundle.
 *
 * TODO(e2ee): the OPSLIMIT/MEMLIMIT below are MODERATE. Real-device test on low-end
 * hardware (minSdk 26) — INTERACTIVE may be needed to keep restore under a couple
 * seconds; whatever we pick MUST be recorded in kdf_params so restore reproduces it.
 */
internal object KeyBackup {

    private const val ALGO = "argon2id"
    // OPSLIMIT_MODERATE is a Long (3L); MEMLIMIT_MODERATE is an Int (256 MiB). The
    // native cryptoPwHash memLimit parameter is a JNA NativeLong, so we wrap it.
    private val OPSLIMIT: Long = PwHash.OPSLIMIT_MODERATE
    private val MEMLIMIT: Long = PwHash.MEMLIMIT_MODERATE.toLong()
    private val SALT_BYTES = PwHash.SALTBYTES // Java static field, not a Kotlin const
    private const val KEY_BYTES = 32

    data class Blob(val ciphertext: String, val salt: String, val params: String)

    /** Encrypt [bundle] under a key derived from [password]. Returns transport-ready base64 fields. */
    fun seal(password: String, bundle: ByteArray): Blob {
        val salt = Sodium.randomBytes(SALT_BYTES)
        val key = derive(password, salt, OPSLIMIT, MEMLIMIT)
        val ct = Sodium.aeadSeal(key, bundle, null)
        val params = JSONObject()
            .put("algo", ALGO)
            .put("ops", OPSLIMIT)
            .put("mem", MEMLIMIT)
            .put("keyBytes", KEY_BYTES)
            .toString()
        return Blob(Sodium.b64(ct), Sodium.b64(salt), params)
    }

    /** Reverse [seal]. Reproduces the KDF from the stored params so cost bumps stay compatible. */
    fun open(password: String, blob: Blob): ByteArray {
        val p = JSONObject(blob.params)
        require(p.optString("algo", ALGO) == ALGO) { "unsupported KDF ${p.optString("algo")}" }
        val ops = p.optLong("ops", OPSLIMIT)
        val mem = p.optLong("mem", MEMLIMIT)
        val salt = Sodium.unb64(blob.salt)
        val key = derive(password, salt, ops, mem)
        return Sodium.aeadOpen(key, Sodium.unb64(blob.ciphertext), null)
    }

    private fun derive(password: String, salt: ByteArray, ops: Long, mem: Long): ByteArray {
        val out = ByteArray(KEY_BYTES)
        val pw = password.toByteArray(Charsets.UTF_8)
        val ok = Sodium.ls.cryptoPwHash(
            out, out.size,
            pw, pw.size,
            salt,
            ops,
            NativeLong(mem),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        if (!ok) error("argon2id derivation failed")
        return out
    }
}
