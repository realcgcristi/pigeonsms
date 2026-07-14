package app.pigeonsms.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class Accent(val key: String, val label: String, val bright: Color, val deep: Color, val on: Color)

val PigeonAccents = listOf(
    Accent("peach", "peach", Color(0xFFFF9D76), Color(0xFFE87F55), Color(0xFF2A150C)),
    Accent("lavender", "lavender", Color(0xFFB8A7F5), Color(0xFF9B86EC), Color(0xFF1B1230)),
    Accent("mint", "mint", Color(0xFF7FD8A4), Color(0xFF5FBF88), Color(0xFF06251A)),
    Accent("sky", "sky", Color(0xFF76BEFF), Color(0xFF55A0E8), Color(0xFF0A1B2A)),
    Accent("rose", "rose", Color(0xFFFF8FB0), Color(0xFFE86F92), Color(0xFF2A0C16)),
    Accent("amber", "amber", Color(0xFFFFC46B), Color(0xFFE8A94D), Color(0xFF2A1E06)),
    Accent("coral", "coral", Color(0xFFFF7E6B), Color(0xFFE85F4D), Color(0xFF2A0E08)),
    Accent("iris", "iris", Color(0xFF9D8CFF), Color(0xFF7E6BE8), Color(0xFF15102A)),
)

fun accentByKey(key: String): Accent {
    if (key.startsWith("custom:")) {
        val parsed = runCatching { android.graphics.Color.parseColor(key.removePrefix("custom:")) }.getOrNull()
        if (parsed != null) {
            val bright = Color(parsed)
            val deep = Color(bright.red * 0.82f, bright.green * 0.82f, bright.blue * 0.82f, 1f)
            val on = if (bright.luminance() > 0.5f) Color(0xFF201018) else Color.White
            return Accent(key, "custom", bright, deep, on)
        }
    }
    return PigeonAccents.firstOrNull { it.key == key } ?: PigeonAccents.first()
}
