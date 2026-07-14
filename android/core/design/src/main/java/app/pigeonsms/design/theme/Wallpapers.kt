package app.pigeonsms.design.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class Wallpaper(val key: String, val label: String, val stops: List<Color>)

val PigeonWallpapers = listOf(
    Wallpaper("none", "none", emptyList()),
    Wallpaper("aurora", "aurora", listOf(Color(0xFF0E2A2A), Color(0xFF13314F), Color(0xFF2A1E4A))),
    Wallpaper("dusk", "dusk", listOf(Color(0xFF2A1330), Color(0xFF3A1B36), Color(0xFF4A2417))),
    Wallpaper("ocean", "ocean", listOf(Color(0xFF07223A), Color(0xFF0B3350), Color(0xFF0E2A44))),
    Wallpaper("ember", "ember", listOf(Color(0xFF2A0E12), Color(0xFF3A1520), Color(0xFF241026))),
    Wallpaper("forest", "forest", listOf(Color(0xFF0C2417), Color(0xFF103021), Color(0xFF0E2A2A))),
    Wallpaper("plum", "plum", listOf(Color(0xFF1B1230), Color(0xFF2A1840), Color(0xFF16122A))),
    Wallpaper("mono", "mono", listOf(Color(0xFF121216), Color(0xFF1A1A20), Color(0xFF101014))),
)

fun wallpaperByKey(key: String?): Wallpaper? =
    if (key == null || key.startsWith("custom:")) null
    else PigeonWallpapers.firstOrNull { it.key == key }?.takeIf { it.key != "none" }

@Composable
fun rememberWallpaperBrush(stops: List<Color>): Brush {
    if (stops.isEmpty()) return Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    if (LocalReducedMotion.current) return Brush.linearGradient(stops)
    val transition = rememberInfiniteTransition(label = "wallpaper")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "wallpaperShift",
    )
    val span = 1400f
    return Brush.linearGradient(
        colors = stops,
        start = Offset(span * shift, 0f),
        end = Offset(span * (1f - shift), span),
    )
}
