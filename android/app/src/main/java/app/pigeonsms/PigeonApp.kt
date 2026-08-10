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

    // Notifications can be tapped before the Compose graph has mounted (or
    // while the account is signed out). Retain the latest target until the
    // logged-in AppShell consumes it.
    private val _pendingNotificationTarget = MutableStateFlow<NotificationTarget?>(null)
    val pendingNotificationTarget: StateFlow<NotificationTarget?> = _pendingNotificationTarget.asStateFlow()
    private val _pendingPairingLink = MutableStateFlow<String?>(null)
    val pendingPairingLink: StateFlow<String?> = _pendingPairingLink.asStateFlow()
    private val _pendingCallTarget = MutableStateFlow<CallTarget?>(null)
    val pendingCallTarget: StateFlow<CallTarget?> = _pendingCallTarget.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        ensureNotificationChannel(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(true)
        .build()

    fun publishIntent(intent: Intent?) {
        intent?.notificationTargetOrNull()?.let { _pendingNotificationTarget.value = it }
        intent?.callTargetOrNull()?.let { _pendingCallTarget.value = it }
        intent?.dataString
            ?.takeIf { app.pigeonsms.pairing.PairingLinks.parse(it) != null }
            ?.let { _pendingPairingLink.value = it }
    }

    fun publishCallTarget(target: CallTarget) {
        _pendingCallTarget.value = target
    }

    fun consumeCallTarget(target: CallTarget? = _pendingCallTarget.value) {
        if (target != null && _pendingCallTarget.value == target) {
            _pendingCallTarget.value = null
        }
    }

    fun consumeNotificationTarget(target: NotificationTarget? = _pendingNotificationTarget.value) {
        if (target != null && _pendingNotificationTarget.value == target) {
            _pendingNotificationTarget.value = null
        }
    }

    fun consumePairingLink(link: String? = _pendingPairingLink.value) {
        if (link != null && _pendingPairingLink.value == link) {
            _pendingPairingLink.value = null
        }
    }
}
