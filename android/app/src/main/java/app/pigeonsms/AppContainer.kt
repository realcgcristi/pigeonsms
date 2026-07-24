package app.pigeonsms

import android.content.Context
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.data.ChatRepository
import app.pigeonsms.data.SessionStore
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.data.ThemeStore
import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.Gateway
import app.pigeonsms.network.PigeonApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/** Manual DI. Single graph; Hilt would be ceremony at this size. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob())
    val sessionStore = SessionStore(context)
    val themeStore = ThemeStore(context)
    val api = PigeonApi(tokenProvider = { sessionStore.session.first()?.token })
    private val db = PigeonDatabase.get(context)

    val authRepository = AuthRepository(api, sessionStore, db)
    val socialRepository = SocialRepository(api)
    val chatRepository = ChatRepository(api, db)
    val gateway = Gateway(api, appScope) { sessionStore.session.first()?.token }
}
