package app.pigeonsms.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val settle by animateFloatAsState(
        targetValue = if (shown || reduced) 1f else 0f,
        animationSpec = PigeonMotion.smooth(),
        label = "emptySettle",
    )
    val glass = LocalLiquidGlass.current
    val accent = MaterialTheme.colorScheme.primary
    val discFill = if (glass) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.30f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
    }
    Column(
        modifier = modifier
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
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                    ),
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (glass) 0.18f else 0.08f),
                            Color.Transparent,
                            accent.copy(alpha = 0.14f),
                        ),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = accent.copy(alpha = 0.85f),
            )
        }
        Text(
            text = title.lowercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.l),
        )
        Text(
            text = subtitle.lowercase(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}
