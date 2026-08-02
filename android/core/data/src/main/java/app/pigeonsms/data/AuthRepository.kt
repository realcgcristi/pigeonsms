package app.pigeonsms.data

import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.HistoryEntry
import app.pigeonsms.network.AuthResponse
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.SessionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class AuthRepository(
    private val api: PigeonApi,
    private val store: SessionStore,
    private val db: PigeonDatabase,
) {
    val session: Flow<LocalSession?> = store.session

    suspend fun checkInvite(code: String) = api.checkInvite(code)

    suspend fun registerPush(token: String) = api.registerPush(token)

    suspend fun signup(invite: String, username: String, email: String, password: String, device: String) {
        accept(api.signup(invite, username, email, password, device))
    }
    suspend fun login(login: String, password: String, device: String, totp: String? = null) {
        accept(api.login(login, password, device, totp))
    }
    suspend fun passkeyAuthenticationOptions(login: String?) = api.passkeyAuthenticationOptions(login)
    suspend fun completePasskeyAuthentication(challengeId: String, response: JsonObject, device: String) {
        accept(api.verifyPasskeyAuthentication(challengeId, response, device))
    }
    suspend fun passkeyRegistrationOptions() = api.passkeyRegistrationOptions()
    suspend fun completePasskeyRegistration(challengeId: String, response: JsonObject, name: String) =
        api.verifyPasskeyRegistration(challengeId, response, name)
    suspend fun passkeys() = api.passkeys()
    suspend fun revokePasskey(id: String) = api.revokePasskey(id)
    suspend fun createPairing() = api.createPairing()
    suspend fun pairings() = api.pairings()
    suspend fun pairing(id: String) = api.pairing(id)
    suspend fun requestPairing(id: String, secret: String, claimSecret: String, device: String) =
        api.requestPairing(id, secret, claimSecret, device)
    suspend fun pairingStatus(id: String, secret: String, claimSecret: String) =
        api.pairingStatus(id, secret, claimSecret)
    suspend fun approvePairing(id: String) = api.approvePairing(id)
    suspend fun denyPairing(id: String) = api.denyPairing(id)
    suspend fun cancelPairing(id: String) = api.cancelPairing(id)
    suspend fun claimPairing(id: String, secret: String, claimSecret: String) {
        accept(api.claimPairing(id, secret, claimSecret))
    }
    suspend fun logout() {
        runCatching { api.logout() }
        clearLocalState()
    }
    suspend fun sessions() = api.sessions()
    suspend fun revokeSession(id: String) = api.revokeSession(id)
    suspend fun history() = api.history()

    suspend fun totpSetup() = api.totpSetup()
    suspend fun totpEnable(code: String) = api.totpEnable(code)
    suspend fun totpDisable(code: String) = api.totpDisable(code)
    suspend fun exportData() = api.exportData()
    suspend fun deleteAccount(password: String) {
        api.deleteAccount(password)
        clearLocalState()
    }

    private suspend fun accept(response: AuthResponse) {
        val user = response.user
        store.save(LocalSession(response.token, user.id, user.username, user.email, user.is_admin))
    }

    private suspend fun clearLocalState() {
        try {
            withContext(Dispatchers.IO) { db.clearAllTables() }
        } finally {
            store.clear()
        }
    }
}
