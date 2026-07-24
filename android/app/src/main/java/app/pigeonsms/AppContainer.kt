package app.pigeonsms

import android.content.Context
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.data.ChatRepository
import app.pigeonsms.data.SessionStore
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.data.ThemeStore
import app.pigeonsms.data.e2ee.DefaultE2eeManager
import app.pigeonsms.data.e2ee.E2eeManager
import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.Gateway
import app.pigeonsms.network.PigeonApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual DI. Single graph; Hilt would be ceremony at this size. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob())
    val sessionStore = SessionStore(context)
    val themeStore = ThemeStore(context)
    val api = PigeonApi(tokenProvider = { sessionStore.session.first()?.token })
    private val db = PigeonDatabase.get(context)

    val authRepository = AuthRepository(api, sessionStore, db)
    val socialRepository = SocialRepository(api)
    /**
     * EXPERIMENTAL E2EE for DMs. The concrete manager is wired here (Keystore-backed
     * identity + Room ratchet-state DAO) so ChatRepository can encrypt/decrypt when the
     * `e2ee` ThemePrefs flag is on. Constructing it is cheap and side-effect-free (keys
     * are minted lazily on first use / device bootstrap).
     */
    val e2eeManager: E2eeManager = DefaultE2eeManager.create(context.applicationContext, api, db)
    val chatRepository = ChatRepository(api, db, e2eeManager)
    val gateway = Gateway(api, appScope, tokenProvider = { sessionStore.session.first()?.token })

    @Volatile private var deviceBootstrapped = false

    init {
        // Register this device's E2EE identity key once, idempotently, off the main
        // thread. Non-fatal: if there's no session yet (fresh install / logged out) or
        // the network is down, we just skip — bootstrapE2ee() re-runs after login.
        appScope.launch { bootstrapE2ee() }
    }

    /**
     * Generate (if needed) this device's identity keypair and publish its pub key to
     * the server so peers can wrap DM keys to it. Idempotent — publishDevice on the
     * manager mints the keypair lazily and re-POSTs the same pub key harmlessly. Best
     * effort: any failure (no token, offline, e2ee flag off) leaves E2EE simply
     * unavailable and messaging falls back to plaintext.
     */
    suspend fun bootstrapE2ee() {
        if (deviceBootstrapped) return
        val loggedIn = runCatching { sessionStore.session.first() != null }.getOrDefault(false)
        if (!loggedIn) return
        val e2eeOn = runCatching { themeStore.prefs.first().e2ee }.getOrDefault(false)
        if (!e2eeOn) return
        runCatching {
            kotlinx.coroutines.withContext(Dispatchers.IO) { e2eeManager.publishDevice() }
        }.onSuccess { deviceBootstrapped = true }
    }
}
