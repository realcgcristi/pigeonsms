package app.pigeonsms.data.e2ee

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Box
import java.security.SecureRandom

/**
 * Thin libsodium (lazysodium) facade for the E2EE stack. Ships FLAG-OFF and is
 * EXPERIMENTAL — see [E2eeManager]. Everything here is byte-array in / byte-array
 * out; base64 is only applied at the transport boundary (envelopes, backups).
 *
 * Primitives used:
 *  - X25519 identity keys + crypto_box_seal (sealed box) to wrap per-DM keys to a
 *    recipient device pub key. The sender is anonymous/ephemeral, which is exactly
 *    what we want for one-shot key delivery via [KeyEnvelopeDto].
 *  - XChaCha20-Poly1305 (crypto_aead) for the ratchet message AEAD — a 24-byte
 *    random nonce sidesteps the counter-reuse footguns of the 12-byte variants.
 *  - HKDF/HMAC-SHA256 (see [Ratchet]) for the symmetric-key and root chains.
 *
 * TODO(e2ee): every path here needs on-real-device validation. lazysodium loads a
 * native .so via JNA; on some ABIs / minified builds the loader needs a keep rule.
 */
object Sodium {
    // LazySodiumAndroid is thread-safe for these stateless calls.
    val ls: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // --- base64 (URL-unsafe standard, no wrap) — matches the server's atob/btoa ---
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // --- X25519 identity keypair ---
    data class KeyPairBytes(val publicKey: ByteArray, val secretKey: ByteArray)

    fun newBoxKeyPair(): KeyPairBytes {
        // Native (byte[],byte[])->boolean form: the Lazy no-arg overload throws a
        // checked SodiumException, which we avoid here.
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        if (!ls.cryptoBoxKeypair(pk, sk)) error("crypto_box_keypair failed")
        return KeyPairBytes(pk, sk)
    }

    /** Derive the X25519 public key from a stored secret (used after Keystore reload). */
    fun publicFromSecret(secret: ByteArray): ByteArray {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        // scalarmult_base: pub = secret · basepoint
        if (!ls.cryptoScalarMultBase(pk, secret)) error("scalarmult_base failed")
        return pk
    }

    // --- sealed box: wrap a symmetric key to a recipient device pub key ---
    fun seal(message: ByteArray, recipientPub: ByteArray): ByteArray {
        val out = ByteArray(message.size + Box.SEALBYTES)
        if (!ls.cryptoBoxSeal(out, message, message.size.toLong(), recipientPub)) {
            error("crypto_box_seal failed")
        }
        return out
    }

    /** Unwrap a sealed box. Needs BOTH our pub + secret (that is how libsodium's API works). */
    fun sealOpen(sealed: ByteArray, ourPub: ByteArray, ourSecret: ByteArray): ByteArray {
        val outLen = sealed.size - Box.SEALBYTES
        require(outLen >= 0) { "sealed box too short" }
        val out = ByteArray(outLen)
        if (!ls.cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), ourPub, ourSecret)) {
            error("crypto_box_seal_open failed (wrong key or corrupt envelope)")
        }
        return out
    }

    // --- XChaCha20-Poly1305 AEAD for ratchet messages ---
    // Not `const val`: these read Java interface static fields, which Kotlin does
    // not treat as compile-time constants.
    val AEAD_KEYBYTES = AEAD.XCHACHA20POLY1305_IETF_KEYBYTES
    val AEAD_NPUBBYTES = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES // 24
    val AEAD_ABYTES = AEAD.XCHACHA20POLY1305_IETF_ABYTES

    /** Returns nonce(24) || ciphertext(+16 tag). [ad] is authenticated but not encrypted. */
    fun aeadSeal(key: ByteArray, plaintext: ByteArray, ad: ByteArray?): ByteArray {
        val nonce = randomBytes(AEAD_NPUBBYTES)
        val ct = ByteArray(plaintext.size + AEAD_ABYTES)
        val ctLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ct, ctLen,
            plaintext, plaintext.size.toLong(),
            ad, (ad?.size ?: 0).toLong(),
            null, nonce, key,
        )
        if (!ok) error("aead encrypt failed")
        return nonce + ct.copyOf(ctLen[0].toInt())
    }

    fun aeadOpen(key: ByteArray, boxed: ByteArray, ad: ByteArray?): ByteArray {
        require(boxed.size >= AEAD_NPUBBYTES + AEAD_ABYTES) { "aead box too short" }
        val nonce = boxed.copyOfRange(0, AEAD_NPUBBYTES)
        val ct = boxed.copyOfRange(AEAD_NPUBBYTES, boxed.size)
        val pt = ByteArray(ct.size - AEAD_ABYTES)
        val ptLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            pt, ptLen,
            null,
            ct, ct.size.toLong(),
            ad, (ad?.size ?: 0).toLong(),
            nonce, key,
        )
        if (!ok) error("aead decrypt failed (auth tag mismatch)")
        return pt.copyOf(ptLen[0].toInt())
    }
}
