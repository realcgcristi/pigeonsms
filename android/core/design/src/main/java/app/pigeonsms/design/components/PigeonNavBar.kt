package app.pigeonsms.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.LocalGlassTint
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
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
    val experimental = LocalExperimentalRedesign.current
    val glass = LocalLiquidGlass.current
    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh

    val novaShape = NovaCorners.card
    val galaxy = app.pigeonsms.design.theme.isGalaxySkin()
    val barModifier = if (experimental && !glass && galaxy) {
        val accent = MaterialTheme.colorScheme.primary
        Modifier

            // over the aurora canvas + a bright accent-lit rim.
            .shadow(
                elevation = NovaDepth.floatingElevation,
                shape = novaShape,
                clip = false,
                spotColor = accent.copy(alpha = NovaDepth.glowSoft),
                ambientColor = Color.Black,
            )
            .clip(novaShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = NovaDepth.rimAccent),
                        Color.White.copy(alpha = NovaDepth.rimTop),
                        accent.copy(alpha = 0.18f),
                    ),
                ),
                novaShape,
            )
    } else if (experimental && !glass) {

        Modifier
            .clip(novaShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                Color.White.copy(alpha = 0.05f),
                novaShape,
            )
    } else if (glass) {
        val tint = lerp(barColor, LocalGlassTint.current, 0.08f)
        Modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.46f), tint.copy(alpha = 0.26f))))
            .drawBehind {
                // top sheen + grounding inner shadow at the bottom edge
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
        val haptics = LocalHapticFeedback.current
        items.forEach { item ->
            val isSel = item.route == selectedRoute
            NavBarItem(
                item = item,
                selected = isSel,
                onClick = {
                    if (!isSel) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect(item)
                },
            )
        }
    }
}

@Composable
private fun NavBarItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val experimental = LocalExperimentalRedesign.current
    val galaxy = app.pigeonsms.design.theme.isGalaxySkin()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary

    // accent pill); classic keeps the tinted accent-on-transparent look.
    val tint by animateColorAsState(
        targetValue = when {
            selected && experimental -> MaterialTheme.colorScheme.onPrimary
            selected -> accent
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = PigeonMotion.snappy(),
        label = "navTint",
    )
    val pillColor by animateColorAsState(
        targetValue = when {
            selected && experimental -> accent
            selected -> accent.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = PigeonMotion.smooth(),
        label = "navPill",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.92f else 1f,
        animationSpec = PigeonMotion.snappy(),
        label = "navPress",
    )
    // the pill scales in on a soft spring — the "settle" of the indicator
    val settle by animateFloatAsState(
        targetValue = if (selected) 1f else 0.7f,
        animationSpec = if (reduced) PigeonMotion.snappy()
        else spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "navSettle",
    )

    val grow = if (experimental) 0.12f else 0.06f
    // the pill's accent glow blooms in on select then eases back — a tab that

    val bloom by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "navBloom",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                scaleX = press * ((1f - grow) + grow * settle)
                scaleY = press * ((1f - grow) + grow * settle)
            }
            .clip(CircleShape)
            .then(
                if (experimental && selected && galaxy) {

                    // behind that blooms in on select.
                    Modifier
                        .background(Brush.horizontalGradient(listOf(accent, lerp(accent, NovaColors.Cyan, 0.55f))))
                        .drawBehind {
                            val r = size.maxDimension * 0.75f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(accent.copy(alpha = 0.30f * bloom), Color.Transparent),
                                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                                    radius = r,
                                ),
                                radius = r,
                                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                            )
                        }
                } else if (experimental && selected) {

                    Modifier.background(accent)
                } else {
                    Modifier.background(pillColor)
                },
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = Dimens.touchTarget)
            .padding(horizontal = Spacing.l),
    ) {
        Crossfade(
            targetState = selected,
            animationSpec = PigeonMotion.snappy(),
            label = "navIcon",
        ) { sel ->
            Icon(
                imageVector = if (sel) item.selectedIcon ?: item.icon else item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
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
