package app.pigeonsms.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.glassPanel

/** Connection lifecycle as reported by the in-WebView JS. */
enum class CallStatus { Connecting, Connected, Reconnecting, Ended }

/** Title + duration ticker + connection chip, floating over the call surface.
 *  [diagLog] is a rolling list of the last few diagnostics lines — shown while the
 *  call is not yet fully connected (or on error) so blind-debugging users can
 *  screenshot exactly which phase failed. */
@Composable
fun CallTopOverlay(
    title: String,
    status: CallStatus,
    durationSeconds: Long,
    errorMessage: String?,
    diagLog: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .glassPanel(MaterialTheme.shapes.extraLarge, tint = MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = Spacing.xl, vertical = Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (status == CallStatus.Connected) formatCallDuration(durationSeconds) else statusLabel(status),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        if (status != CallStatus.Connected || errorMessage != null) {
            Box(Modifier.padding(top = Spacing.s)) {
                val (label, color) = when {
                    errorMessage != null -> errorMessage to MaterialTheme.colorScheme.error
                    status == CallStatus.Connecting -> "connecting…" to Color(0xFFE8B04B)
                    status == CallStatus.Reconnecting -> "reconnecting…" to Color(0xFFE8B04B)
                    else -> "call ended" to Color.White.copy(alpha = 0.6f)
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.30f), CircleShape)
                        .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                )
            }
        }

        // Verbose diagnostics log — visible until the call is fully connected, or
        // whenever there's an error. Scrollable; shows the last several phases.
        if (diagLog.isNotEmpty() && (status != CallStatus.Connected || errorMessage != null)) {
            val scroll = rememberScrollState()
            LaunchedEffect(diagLog.size) { scroll.scrollTo(scroll.maxValue) }
            Column(
                Modifier
                    .padding(top = Spacing.s)
                    .fillMaxWidth()
                    .heightIn(max = 132.dp)
                    .background(Color(0xCC0B0E16), MaterialTheme.shapes.medium)
                    .verticalScroll(scroll)
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
            ) {
                diagLog.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.startsWith("‼")) Color(0xFFFF9EA8) else Color.White.copy(alpha = 0.82f),
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: CallStatus) = when (status) {
    CallStatus.Connecting -> "connecting…"
    CallStatus.Connected -> "connected"
    CallStatus.Reconnecting -> "reconnecting…"
    CallStatus.Ended -> "call ended"
}

fun formatCallDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Bottom liquid-glass pill with the call controls; springs in from below. */
@Composable
fun CallControlBar(
    visible: Boolean,
    video: Boolean,
    muted: Boolean,
    speakerOn: Boolean,
    cameraOff: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } + fadeIn(),
        exit = slideOutVertically { it * 2 } + fadeOut(),
    ) {
        Row(
            Modifier
                .glassPanel(CircleShape, tint = MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallControlButton(
                icon = if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                description = if (muted) "unmute" else "mute",
                active = muted,
                onClick = onToggleMute,
            )
            CallControlButton(
                icon = if (speakerOn) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                description = if (speakerOn) "earpiece" else "speaker",
                active = speakerOn,
                onClick = onToggleSpeaker,
            )
            if (video) {
                CallControlButton(
                    icon = if (cameraOff) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam,
                    description = if (cameraOff) "camera on" else "camera off",
                    active = cameraOff,
                    onClick = onToggleCamera,
                )
                CallControlButton(
                    icon = Icons.Outlined.Cameraswitch,
                    description = "flip camera",
                    active = false,
                    onClick = onSwitchCamera,
                )
            }
            // end call — red, always last
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD94B63))
                    .clickableScale(pressedScale = 0.90f, onClick = onEndCall),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CallEnd, "end call", tint = Color.White)
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f))
            .clickableScale(pressedScale = 0.90f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White)
    }
}
