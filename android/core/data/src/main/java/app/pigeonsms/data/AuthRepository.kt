package app.pigeonsms.data

import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.HistoryEntry
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.SessionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AuthRepository(
    private val api: PigeonApi,
    private val store: SessionStore,
    private val db: PigeonDatabase,
) {
    val session: Flow<LocalSession?> = store.session

    suspend fun checkInvite(code: String) = api.checkInvite(code)

    suspend fun registerPush(token: String) = api.registerPush(token)

    suspend fun signup(invite: String, username: String, email: String, password: String, device: String) {
        val r = api.signup(invite, username, email, password, device)
        store.save(LocalSession(r.token, r.user.id, r.user.username, r.user.email, r.user.is_admin))
    }
    suspend fun login(login: String, password: String, device: String, totp: String? = null) {
        val r = api.login(login, password, device, totp)
        store.save(LocalSession(r.token, r.user.id, r.user.username, r.user.email, r.user.is_admin))
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

    private suspend fun clearLocalState() {
        try {
            withContext(Dispatchers.IO) { db.clearAllTables() }
        } finally {
            store.clear()
        }
    }
}
