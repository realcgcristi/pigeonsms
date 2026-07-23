package app.pigeonsms.design.components

import androidx.compose.ui.draw.shadow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.heroAppear
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaSurface

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
fun NovaPanel(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = NovaCorners.card,
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    accented: Boolean = false,
    glow: Boolean = false,
    elevation: Dp = NovaDepth.cardElevation,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(Spacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .novaElevation(shape, tint, accent, accented = accented, glow = glow, elevation = elevation)
            .padding(contentPadding),
        content = content,
    )
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
fun NovaHero(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentSubtitle: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Spacing.xl, end = Spacing.l, top = Spacing.l, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).heroAppear()) {
            Text(
                title.lowercase(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle.lowercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (accentSubtitle) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
        }
        if (action != null) {
            Box(Modifier.heroAppear(delayMillis = 60)) { action() }
        }
    }
}

@Composable
fun NovaIconBadgeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !reduced) 0.9f else 1f, NovaMotion.press(), label = "badgePress",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .novaElevation(NovaCorners.iconBadge, MaterialTheme.colorScheme.surfaceContainerHigh, accent, accented = true)
            .clickable(interactionSource = source, indication = ripple(bounded = true), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides accent) { content() }
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
fun NovaTag(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = NovaCorners.chip
    val galaxy = app.pigeonsms.design.theme.isGalaxySkin()
    val base = if (selected) {
        Modifier
            .clip(shape)

            // clean solid accent — no gradient slab.
            .then(
                if (galaxy) Modifier.background(Brush.horizontalGradient(NovaGradients.cta(accent)))
                else Modifier.background(accent),
            )
    } else {
        Modifier.novaSurface(shape, MaterialTheme.colorScheme.surfaceContainerHigh, accent)
    }
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .then(base)
            .then(clickMod)
            .heightIn(min = 34.dp)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
fun NovaPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    armed: Boolean = true,
    height: Dp = 56.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = NovaCorners.button
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !reduced && armed) 0.96f else 1f, NovaMotion.press(), label = "ctaPress",
    )
    val galaxy = app.pigeonsms.design.theme.isGalaxySkin()
    val fill = if (armed) {
        Modifier

            // solid accent fill, no glow, no gradient.
            .then(if (galaxy) Modifier.androidx_shadowGlow(shape, accent) else Modifier)
            .clip(shape)
            .then(
                if (galaxy) Modifier.background(Brush.linearGradient(NovaGradients.cta(accent)))
                else Modifier.background(accent),
            )
    } else {
        Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .fillMaxWidth()
            .then(fill)
            .heightIn(min = height)
            .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
            .padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cc = if (armed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        CompositionLocalProvider(LocalContentColor provides cc) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(Spacing.s))
            }
            Text(text.lowercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cc)
        }
    }
}

private fun Modifier.androidx_shadowGlow(shape: androidx.compose.ui.graphics.Shape, accent: Color): Modifier =
    this.shadow(
        elevation = NovaDepth.raisedElevation,
        shape = shape,
        clip = false,
        spotColor = accent.copy(alpha = NovaDepth.glowStrong),
        ambientColor = Color.Black,
    )

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
fun NovaSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Bold,
        color = if (accent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = Spacing.xs),
    )
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
fun NovaAnimatedCount(
    count: Int,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = LocalContentColor.current,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            if (reduced) {
                fadeIn() togetherWith fadeOut() using SizeTransform(clip = false)
            } else {
                val up = targetState > initialState
                (slideInVertically { h -> if (up) h else -h } + fadeIn()) togetherWith
                    (slideOutVertically { h -> if (up) -h else h } + fadeOut()) using SizeTransform(clip = false)
            }
        },
        modifier = modifier.animateContentSize(),
        label = "novaCount",
    ) { c ->
        Text("$c", style = style, color = color, fontWeight = FontWeight.Bold)
    }
}
