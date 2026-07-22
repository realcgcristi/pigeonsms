package app.pigeonsms

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

class PigeonApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    private val _pendingNotificationTarget = MutableStateFlow<NotificationTarget?>(null)
    val pendingNotificationTarget: StateFlow<NotificationTarget?> = _pendingNotificationTarget.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        ensureNotificationChannel(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(true)
        .build()

    fun publishNotificationIntent(intent: Intent?) {
        intent?.notificationTargetOrNull()?.let { _pendingNotificationTarget.value = it }
    }

    fun consumeNotificationTarget(target: NotificationTarget? = _pendingNotificationTarget.value) {
        if (target != null && _pendingNotificationTarget.value == target) {
            _pendingNotificationTarget.value = null
        }
    }
}
