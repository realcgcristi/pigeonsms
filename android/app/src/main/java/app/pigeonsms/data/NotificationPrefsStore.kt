package app.pigeonsms.data

import android.content.Context

/** Small, synchronous local mirror of notification controls.
 *
 * The server owns the same scopes (global, space, channel and user). Keeping a
 * device copy lets FCM/background delivery and the settings UI agree even
 * while the app is offline; the API sync can be added without changing callers.
 */
data class NotificationPrefs(
    val mode: String = "all", // all | mentions | mute
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val badge: Boolean = true,
)

class NotificationPrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

    private fun key(scopeType: String, scopeId: String) = "$scopeType:${scopeId.ifBlank { "*" }}"
    private fun value(scopeType: String, scopeId: String, field: String) = "${key(scopeType, scopeId)}.$field"

    fun get(scopeType: String = "global", scopeId: String = ""): NotificationPrefs = NotificationPrefs(
        mode = prefs.getString(value(scopeType, scopeId, "mode"), null) ?: "all",
        sound = prefs.getBoolean(value(scopeType, scopeId, "sound"), true),
        vibration = prefs.getBoolean(value(scopeType, scopeId, "vibration"), true),
        badge = prefs.getBoolean(value(scopeType, scopeId, "badge"), true),
    )

    fun put(scopeType: String, scopeId: String = "", next: NotificationPrefs) {
        prefs.edit()
            .putString(value(scopeType, scopeId, "mode"), next.mode)
            .putBoolean(value(scopeType, scopeId, "sound"), next.sound)
            .putBoolean(value(scopeType, scopeId, "vibration"), next.vibration)
            .putBoolean(value(scopeType, scopeId, "badge"), next.badge)
            .apply()
    }

    fun enabledForPush(): Boolean = get().mode != "mute"
    fun showBadge(): Boolean = get().badge
}
