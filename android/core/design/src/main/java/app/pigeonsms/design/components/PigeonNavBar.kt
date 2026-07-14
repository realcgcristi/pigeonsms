package app.pigeonsms.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalGlassTint
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing

data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector? = null,
)

@Composable
fun PigeonNavBar(
    items: List<NavItem>,
    selectedRoute: String,
    onSelect: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalLiquidGlass.current
    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val barModifier = if (glass) {
        val tint = lerp(barColor, LocalGlassTint.current, 0.08f)
        Modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.46f), tint.copy(alpha = 0.26f))))
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.06f),
                        0.35f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.08f),
                    ),
                )
            }
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.03f),
                    ),
                ),
                CircleShape,
            )
    } else {
        Modifier.clip(CircleShape).background(barColor)
    }
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .fillMaxWidth()
            .then(barModifier)
            .padding(horizontal = Spacing.s, vertical = Spacing.s),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            NavBarItem(
                item = item,
                selected = item.route == selectedRoute,
                onClick = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun NavBarItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val tint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = PigeonMotion.snappy(),
        label = "navTint",
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = PigeonMotion.smooth(),
        label = "navPill",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.92f else 1f,
        animationSpec = PigeonMotion.snappy(),
        label = "navPress",
    )
    val settle by animateFloatAsState(
        targetValue = if (selected) 1f else 0.7f,
        animationSpec = if (reduced) PigeonMotion.snappy()
        else spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "navSettle",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                scaleX = press * (0.94f + 0.06f * settle)
                scaleY = press * (0.94f + 0.06f * settle)
            }
            .clip(CircleShape)
            .background(pillColor)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = Dimens.touchTarget)
            .padding(horizontal = Spacing.l),
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon ?: item.icon else item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkHorizontally(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) + fadeOut(),
        ) {
            Text(
                text = item.label.lowercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = Spacing.s),
            )
        }
    }
}
