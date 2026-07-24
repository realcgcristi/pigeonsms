package app.pigeonsms.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = Spacing.l, start = Spacing.xl, end = Spacing.l, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title.lowercase(),
                style = MaterialTheme.typography.headlineMedium,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle.lowercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
        }
        action?.invoke()
    }
}

/** @param action optional CTA rendered centered under the empty caption (e.g.
 *  "start a chat"), so an empty state can offer the action its copy names. */
@Composable
fun Empty(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = androidx.compose.material.icons.Icons.Outlined.ChatBubbleOutline,
    action: (@Composable () -> Unit)? = null,
) {
    if (action == null) {
        app.pigeonsms.design.components.EmptyState(icon = icon, title = title, subtitle = subtitle)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            app.pigeonsms.design.components.EmptyState(icon = icon, title = title, subtitle = subtitle)
            // action floats just below the centered caption block
            Column(
                Modifier.fillMaxSize().padding(bottom = Spacing.huge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) { action() }
        }
    }
}

/** First-load state. On the Nova path this is a card-skeleton list (matching the
 *  loaded UI); classic/glass keep the calm centered spinner. */
@Composable
fun LoadingState(label: String) {
    if (app.pigeonsms.design.theme.LocalExperimentalRedesign.current) {
        SkeletonList()
        return
    }
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.m))
    }
}

/** Error twin of [app.pigeonsms.design.components.EmptyState]: same icon-disc +
 *  title + caption layout and gentle settle-in, but washed in the error tone,
 *  with a retry action underneath. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val settle by animateFloatAsState(
        targetValue = if (shown || reduced) 1f else 0f,
        animationSpec = PigeonMotion.smooth(),
        label = "errorSettle",
    )
    val glass = LocalLiquidGlass.current
    val experimental = app.pigeonsms.design.theme.LocalExperimentalRedesign.current
    val tone = MaterialTheme.colorScheme.error
    val discFill = if (glass) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.30f),
            ),
        )
    } else if (experimental) {
        // Nova: mirror EmptyState's washed disc, in the error tone
        Brush.verticalGradient(listOf(tone.copy(alpha = 0.26f), tone.copy(alpha = 0.12f)))
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(Spacing.xl)
            .graphicsLayer {
                alpha = settle
                val s = 0.94f + 0.06f * settle
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(discFill)
                .background(Brush.radialGradient(listOf(tone.copy(alpha = 0.10f), Color.Transparent)))
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (glass || experimental) 0.16f else 0.08f),
                            Color.Transparent,
                            tone.copy(alpha = if (experimental) 0.24f else 0.14f),
                        ),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(34.dp), tint = tone.copy(alpha = 0.85f))
        }
        Text(
            "something went wrong",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.l),
        )
        Text(
            message.lowercase(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.s),
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = Spacing.l)) { Text("try again") }
    }
}
