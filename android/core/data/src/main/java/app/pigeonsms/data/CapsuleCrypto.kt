package app.pigeonsms.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedCapsule(
    val ciphertext: String,
    val iv: String,
    val salt: String,
    val kdf: String = "pbkdf2-sha256-250000",
)

object CapsuleCrypto {
    private const val iterations = 250_000
    private val random = SecureRandom()

    fun encrypt(value: String, password: String): EncryptedCapsule {
        require(password.length >= 8) { "use at least 8 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return EncryptedCapsule(
            ciphertext = encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8))),
            iv = encode(iv),
            salt = encode(salt),
        )
    }

    fun decrypt(capsule: EncryptedCapsule, password: String): String {
        require(capsule.kdf == "pbkdf2-sha256-250000") { "unsupported checkpoint encryption" }
        val iv = decode(capsule.iv)
        val salt = decode(capsule.salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(decode(capsule.ciphertext)).toString(Charsets.UTF_8)
    }

    fun digest(ciphertext: String): String {
        val canonical = encode(decode(ciphertext))
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.DEFAULT)
}
