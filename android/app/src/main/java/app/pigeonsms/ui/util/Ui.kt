package app.pigeonsms.ui.util

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.PigeonColors
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Calendar

// design/Palette.kt is the single source of truth for avatar disc colors
private val avatarColors = PigeonColors.AvatarPalette

@Composable
fun Avatar(name: String, model: Any?, size: Dp = 40.dp, modifier: Modifier = Modifier, sharedKey: String? = null) {
    val shared = if (sharedKey != null) modifier.sharedAvatar(sharedKey) else modifier
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = "$name avatar",
            contentScale = ContentScale.Crop,
            modifier = shared.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
        )
    } else {
        val color = avatarColors[(name.hashCode() and Int.MAX_VALUE) % avatarColors.size]
        Box(shared.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = PigeonColors.OnAvatar, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// java.time formatters: no Context, so they're safe on API 36 where
// DateUtils.formatDateTime(null, ...) NPEs inside is24HourFormat.
private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun format(ms: Long, fmt: DateTimeFormatter): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt).lowercase()

fun smartTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        sameDay(ms, now) -> format(ms, timeFmt)
        diff < 7 * 86_400_000L -> DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.DAY_IN_MILLIS).toString().lowercase()
        else -> format(ms, dateFmt)
    }
}

fun dayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ms, now) -> "today"
        sameDay(ms, now - 86_400_000) -> "yesterday"
        else -> format(ms, dateFmt)
    }
}

fun presence(lastOnline: Long?): Boolean {
    if (lastOnline == null) return false
    return System.currentTimeMillis() - lastOnline < 5 * 60_000
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}
