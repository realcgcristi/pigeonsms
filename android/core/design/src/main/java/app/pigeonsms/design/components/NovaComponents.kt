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

/**
 * NOVA COMPONENTS — drop-in, richly-styled primitives for the experimental
 * redesign. All are self-contained and Nova-styled by construction (no flag
 * branch inside): use them only on the Nova path. Names are distinct from any
 * screen-local composables so imports never clash.
 */

// ---------------------------------------------------------------------------
// NovaPanel — the canonical elevated Nova card container.
// ---------------------------------------------------------------------------

/**
 * The everyday Nova container: soft accent-tinted drop shadow + lifted-top
 * gradient fill + lit hairline rim (via [novaElevation]). Use for cards, hero
 * bodies, grouped sections — anything that should float over the aurora.
 *
 * @param accented lights the rim with the accent (unread / active state).
 * @param glow tints the drop shadow with the accent — for floating/hero cards.
 */
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
// NovaHero — the unified top-of-screen header (title + stat + trailing action).
// ---------------------------------------------------------------------------

/**
 * The shared Nova screen header so every tab inherits the same hero rhythm: a
 * loud display title, an optional stat subline, and an optional trailing rounded
 * icon-badge action. Title fades + rises in via [heroAppear]. Drop this in place
 * of classic ScreenHeader on the Nova path.
 *
 * @param subtitle a short stat line (e.g. "12 chats · 3 unread"); rendered in
 *   the accent when [accentSubtitle] is set.
 * @param action optional trailing composable, typically a [NovaIconBadgeButton].
 */
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

/** Rounded-square accent icon-badge button — the trailing affordance for
 *  [NovaHero] (add friend, new post, etc.). Presses with a spring. */
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
// NovaTag — pill chip with an optional accent-filled selected state.
// ---------------------------------------------------------------------------

/**
 * Nova pill chip. Neutral tinted by default; [selected] fills it with the
 * accent-gradient CTA wash and flips the content to on-accent ink. Use for
 * filters, sort toggles, role/pronoun chips — one consistent chip everywhere.
 */
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
            // Galaxy fills with the iris→cyan CTA gradient; Nova (flat) uses a
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
// NovaPillButton — the primary gradient CTA with press physics.
// ---------------------------------------------------------------------------

/**
 * Primary Nova CTA: a full-width iris→cyan gradient pill with a soft accent
 * glow-shadow and a spring press-scale. When [armed] is false (e.g. disabled /
 * empty input) it dims to a flat neutral fill. Use for onboarding CTAs, send
 * confirmations, "create" actions.
 */
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
            // GALAXY: accent glow-shadow + iris→cyan gradient. NOVA (flat): a clean
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
// NovaSectionLabel — the uppercase tracked section header.
// ---------------------------------------------------------------------------

/** The uppercase, letter-tracked Nova section label ("channels", "details").
 *  One definition kills the per-screen drift in tracking/alpha/padding. */
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
// NovaAnimatedCount — sliding-digit counter for the numbers users watch tick.
// ---------------------------------------------------------------------------

/**
 * Reduced-motion-aware animated counter: digits slide+fade up on increase, down
 * on decrease. Route unread pills, space badges and reply counts through this so
 * the numbers users watch actually animate. Snaps under reduced motion.
 */
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
