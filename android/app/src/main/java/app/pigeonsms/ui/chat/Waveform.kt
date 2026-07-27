package app.pigeonsms.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Deterministic pseudo-waveform derived from a stable seed (an attachment url).
 * We don't decode the audio, so this guarantees a given voice message always
 * draws the same bar pattern on every device — enough to read as "a waveform"
 * and to fill left→right as it plays.
 */
fun pseudoWaveform(seed: String, bars: Int = 42): List<Float> {
    var h = (seed.hashCode().toLong() and 0xffffffffL) xor 2654435769L
    val out = ArrayList<Float>(bars)
    repeat(bars) {
        h = h * 6364136223846793005L + 1442695040888963407L
        val v = ((h ushr 33).toInt() and 0xff) / 255f
        out.add(0.16f + 0.84f * (0.28f + 0.72f * v))
    }
    return out
}

/**
 * Bar waveform. Bars whose center falls left of [progress] paint [playedColor];
 * the rest paint [unplayedColor] — so playback "fills" gray→bright as it plays,
 * and the recorder can pass progress=1f to keep every live bar lit.
 */
@Composable
fun Waveform(
    amps: List<Float>,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
) {
    Canvas(modifier.height(height)) {
        val n = amps.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val bw = max(2f, slot * 0.55f)
        amps.forEachIndexed { i, a ->
            val x = i * slot + (slot - bw) / 2f
            val bh = max(3f, a.coerceIn(0f, 1f) * size.height)
            val y = (size.height - bh) / 2f
            val played = (i + 0.5f) / n <= progress
            drawRoundRect(
                color = if (played) playedColor else unplayedColor,
                topLeft = Offset(x, y),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f),
            )
        }
    }
}

/** mm:ss from milliseconds. */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
