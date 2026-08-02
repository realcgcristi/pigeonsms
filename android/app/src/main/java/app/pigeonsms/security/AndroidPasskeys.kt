package app.pigeonsms.security

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.network.PigeonApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object AndroidPasskeys {
    suspend fun signIn(activity: Activity, auth: AuthRepository, login: String?, deviceName: String) {
        requireSupported()
        val pending = auth.passkeyAuthenticationOptions(login?.takeIf { it.isNotBlank() })
        val request = GetCredentialRequest(
            credentialOptions = listOf(
                GetPublicKeyCredentialOption(requestJson = pending.options.toString()),
            ),
            preferImmediatelyAvailableCredentials = false,
        )
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val credential = result.credential as? PublicKeyCredential
            ?: throw IllegalStateException("the credential provider returned an unsupported sign-in")
        val response = Json.parseToJsonElement(credential.authenticationResponseJson).jsonObject
        auth.completePasskeyAuthentication(pending.challenge_id, response, deviceName)
    }

    suspend fun register(activity: Activity, auth: AuthRepository, name: String) {
        requireSupported()
        val pending = auth.passkeyRegistrationOptions()
        val request = CreatePublicKeyCredentialRequest(requestJson = pending.options.toString())
        val result = CredentialManager.create(activity).createCredential(activity, request)
        val credential = result as? CreatePublicKeyCredentialResponse
            ?: throw IllegalStateException("the credential provider returned an unsupported passkey")
        val response = Json.parseToJsonElement(credential.registrationResponseJson).jsonObject
        auth.completePasskeyRegistration(pending.challenge_id, response, name)
    }

    fun message(error: Throwable): String? = when (error) {
        is CreateCredentialCancellationException,
        is GetCredentialCancellationException -> null
        is NoCredentialException -> "no passkey is available for this account"
        is PigeonApiException -> error.message
        else -> error.message?.takeIf { it.isNotBlank() } ?: "passkeys are unavailable on this device"
    }

    private fun requireSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw IllegalStateException("passkeys require android 9 or newer")
        }
    }
}
