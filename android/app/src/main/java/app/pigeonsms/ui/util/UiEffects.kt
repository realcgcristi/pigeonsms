package app.pigeonsms.ui.util

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Corners

/**
 * Clickable that springs down slightly while pressed — tactile depth on rows and
 * cards. Uses its own interaction source so the scale reacts to the very press
 * that fires [onClick].
 */
fun Modifier.clickableScale(pressedScale: Float = 0.97f, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = app.pigeonsms.design.theme.LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reduced) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "clickableScale",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
}

/** Press-scale without click handling — layer under Buttons/Surfaces that own
 *  their own click. Pass the same interaction source the clickable uses. */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val reduced = app.pigeonsms.design.theme.LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reduced) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Animated shimmer sweep used by skeleton placeholders. */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 800f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "shimmerX",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + 400f, 0f),
    )
}

@Composable
private fun ShimmerBlock(modifier: Modifier) {
    Box(modifier.clip(Corners.chip).background(shimmerBrush()))
}

/** A single list-row skeleton: circular avatar + two text lines. */
@Composable
fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(shimmerBrush()))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(Modifier.fillMaxWidth(0.45f).height(14.dp))
            ShimmerBlock(Modifier.fillMaxWidth(0.75f).height(12.dp))
        }
    }
}

/** Full-screen skeleton list shown while first data loads. On the Nova path it
 *  swaps to rounded card-shaped placeholders that echo the Nova card silhouette
 *  so first-load matches the loaded UI instead of a bare spinner. */
@Composable
fun SkeletonList(rows: Int = 8, contentPadding: PaddingValues = PaddingValues(vertical = 8.dp)) {
    if (app.pigeonsms.design.theme.LocalExperimentalRedesign.current) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(rows) { SkeletonCard() }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(contentPadding)) {
            repeat(rows) { SkeletonRow() }
        }
    }
}

/** A Nova card-shaped skeleton: rounded block with an avatar disc + two lines,
 *  matching the [Modifier.glassCard] Nova silhouette. */
@Composable
fun SkeletonCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(app.pigeonsms.design.theme.NovaCorners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(shimmerBrush()))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShimmerBlock(Modifier.fillMaxWidth(0.5f).height(15.dp))
            ShimmerBlock(Modifier.fillMaxWidth(0.8f).height(12.dp))
        }
    }
}

// --- Shared-element transitions -------------------------------------------------
// The two scopes are threaded via composition locals so only AppShell + Avatar
// need to know about them; screens stay ignorant.

val LocalSharedTransitionScope =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalNavAnimatedScope =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/**
 * Marks a composable as a shared element keyed by [key]. When both scopes are
 * present (inside the nav graph), the element morphs between screens — e.g. a
 * list avatar flies into the profile header. No-op when scopes are absent.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedAvatar(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedAvatar.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = anim,
        )
    }
}
