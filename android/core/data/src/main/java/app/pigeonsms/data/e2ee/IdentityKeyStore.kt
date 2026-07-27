package app.pigeonsms.data.e2ee

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the device's X25519 identity keypair. The private key is EXPERIMENTAL
 * and ships flag-OFF.
 *
 * WHY WE DON'T STORE X25519 DIRECTLY IN KEYSTORE: Android Keystore's asymmetric
 * support is EC (P-256/…) + RSA — there is no X25519/curve25519 key type, and no
 * way to run libsodium's scalar-mult inside the TEE. So the pattern here is the
 * standard "wrap, don't hold": we keep the raw X25519 secret encrypted at rest
 * under an AES-256-GCM key that DOES live in the AndroidKeyStore (hardware-backed
 * where available, StrongBox not required). The X25519 secret itself only exists
 * in-process, decrypted on demand.
 *
 * TODO(e2ee): real-device test the Keystore-backed AES key on API 26 (minSdk) —
 * setUnlockedDeviceRequired / setUserAuthenticationRequired are intentionally OFF
 * so background send/receive works; revisit if we want at-rest-behind-lock.
 * TODO(e2ee): consider StrongBox (setIsStrongBoxBacked) on devices that support it.
 */
private val Context.e2eeStore by preferencesDataStore(name = "pigeon_e2ee")

internal class IdentityKeyStore(private val context: Context) {

    private object K {
        val secret = stringPreferencesKey("x25519_secret_wrapped") // iv||ct, base64
        val public = stringPreferencesKey("x25519_public")         // base64
        val deviceId = stringPreferencesKey("device_id")           // server-assigned
    }

    /** Load the identity keypair, generating + persisting one on first use. */
    suspend fun getOrCreate(): Sodium.KeyPairBytes {
        loadPersisted()?.let { return it }
        val kp = Sodium.newBoxKeyPair()
        persist(kp)
        return kp
    }

    suspend fun peek(): Sodium.KeyPairBytes? = loadPersisted()

    suspend fun deviceId(): String? = context.e2eeStore.data.firstOrNull()?.get(K.deviceId)

    suspend fun setDeviceId(id: String) = context.e2eeStore.edit { it[K.deviceId] = id }

    /**
     * Overwrite the identity with keys recovered from a key backup. Callers that
     * do this MUST re-publish the device (the server device id / envelopes are tied
     * to the pub key). See [E2eeManager.restoreKeyBackup].
     */
    suspend fun importSecret(secret: ByteArray) {
        val pub = Sodium.publicFromSecret(secret)
        persist(Sodium.KeyPairBytes(pub, secret))
    }

    private suspend fun loadPersisted(): Sodium.KeyPairBytes? {
        val prefs = context.e2eeStore.data.firstOrNull() ?: return null
        val wrapped = prefs[K.secret] ?: return null
        val pub = prefs[K.public] ?: return null
        val secret = runCatching { unwrap(Sodium.unb64(wrapped)) }.getOrNull() ?: return null
        return Sodium.KeyPairBytes(Sodium.unb64(pub), secret)
    }

    private suspend fun persist(kp: Sodium.KeyPairBytes) {
        val wrapped = wrap(kp.secretKey)
        context.e2eeStore.edit {
            it[K.secret] = Sodium.b64(wrapped)
            it[K.public] = Sodium.b64(kp.publicKey)
        }
    }

    // --- AES-256-GCM wrap of the X25519 secret under a Keystore key ---
    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return iv + ct // iv is 12 bytes for GCM
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val iv = wrapped.copyOfRange(0, GCM_IV_LEN)
        val ct = wrapped.copyOfRange(GCM_IV_LEN, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pigeon_e2ee_identity_wrap"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_IV_LEN = 12
    }
}
