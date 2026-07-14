package app.pigeonsms.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pigeon_prefs")

data class LocalSession(val token: String, val userId: String, val username: String, val email: String, val isAdmin: Boolean)

class SessionStore(private val context: Context) {
    private object K {
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val username = stringPreferencesKey("username")
        val email = stringPreferencesKey("email")
        val admin = booleanPreferencesKey("is_admin")
    }
    val session: Flow<LocalSession?> = context.dataStore.data.map { p ->
        val token = p[K.token] ?: return@map null
        LocalSession(token, p[K.userId] ?: "", p[K.username] ?: "", p[K.email] ?: "", p[K.admin] ?: false)
    }
    suspend fun save(s: LocalSession) = context.dataStore.edit {
        it[K.token] = s.token; it[K.userId] = s.userId; it[K.username] = s.username
        it[K.email] = s.email; it[K.admin] = s.isAdmin
    }
    suspend fun clear() = context.dataStore.edit {
        it.remove(K.token); it.remove(K.userId); it.remove(K.username); it.remove(K.email); it.remove(K.admin)
    }
}

enum class ThemeMode { System, Dark, Oled, Light }

data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.Dark,
    val accent: String = "peach",
    val reducedMotion: Boolean = false,
    val readReceipts: Boolean = true,
    val invisible: Boolean = false,
    val wallpaper: String? = null,
    val wallpaperDim: Float = 0.3f,
    val liquidGlass: Boolean = false,
    val dynamicColor: Boolean = false,
)

class ThemeStore(private val context: Context) {
    private object K {
        val mode = stringPreferencesKey("theme_mode")
        val accent = stringPreferencesKey("accent")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
        val readReceipts = booleanPreferencesKey("read_receipts")
        val invisible = booleanPreferencesKey("invisible")
        val wallpaper = stringPreferencesKey("app_wallpaper")
        val wallpaperDim = floatPreferencesKey("app_wallpaper_dim")
        val liquidGlass = booleanPreferencesKey("liquid_glass")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }
    val prefs: Flow<ThemePrefs> = context.dataStore.data.map { p ->
        ThemePrefs(
            mode = runCatching { ThemeMode.valueOf(p[K.mode] ?: "Dark") }.getOrDefault(ThemeMode.Dark),
            accent = p[K.accent] ?: "peach",
            reducedMotion = p[K.reducedMotion] ?: false,
            readReceipts = p[K.readReceipts] ?: true,
            invisible = p[K.invisible] ?: false,
            wallpaper = p[K.wallpaper],
            wallpaperDim = p[K.wallpaperDim] ?: 0.3f,
            liquidGlass = p[K.liquidGlass] ?: false,
            dynamicColor = p[K.dynamicColor] ?: false,
        )
    }
    suspend fun setMode(m: ThemeMode) = context.dataStore.edit { it[K.mode] = m.name }
    suspend fun setAccent(a: String) = context.dataStore.edit { it[K.accent] = a }
    suspend fun setReducedMotion(v: Boolean) = context.dataStore.edit { it[K.reducedMotion] = v }
    suspend fun setReadReceipts(v: Boolean) = context.dataStore.edit { it[K.readReceipts] = v }
    suspend fun setInvisible(v: Boolean) = context.dataStore.edit { it[K.invisible] = v }
    suspend fun setWallpaper(key: String?) = context.dataStore.edit {
        if (key == null) it.remove(K.wallpaper) else it[K.wallpaper] = key
    }
    suspend fun setWallpaperDim(v: Float) = context.dataStore.edit { it[K.wallpaperDim] = v }
    suspend fun setLiquidGlass(v: Boolean) = context.dataStore.edit { it[K.liquidGlass] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.dynamicColor] = v }
}

data class ChatAppearance(val wallpaper: String?, val accent: String?, val muted: Boolean = false)

class ChatAppearanceStore(private val context: Context) {
    private fun wallpaperKey(channelId: String) = stringPreferencesKey("chat_wp_$channelId")
    private fun accentKey(channelId: String) = stringPreferencesKey("chat_accent_$channelId")
    private fun mutedKey(channelId: String) = booleanPreferencesKey("chat_muted_$channelId")

    fun appearance(channelId: String): Flow<ChatAppearance> = context.dataStore.data.map { p ->
        ChatAppearance(p[wallpaperKey(channelId)], p[accentKey(channelId)], p[mutedKey(channelId)] ?: false)
    }

    suspend fun setWallpaper(channelId: String, key: String?) = context.dataStore.edit {
        if (key == null) it.remove(wallpaperKey(channelId)) else it[wallpaperKey(channelId)] = key
    }

    suspend fun setAccent(channelId: String, key: String?) = context.dataStore.edit {
        if (key == null) it.remove(accentKey(channelId)) else it[accentKey(channelId)] = key
    }

    suspend fun setMuted(channelId: String, muted: Boolean) = context.dataStore.edit {
        it[mutedKey(channelId)] = muted
    }
}
