package app.pigeonsms.design.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Skin split: the same Nova primitives render two looks. Galaxy = the deep,
// glowy, gradient-mesh treatment (rich shadows, lit rims, aurora). Nova = a
// flatter, cleaner treatment (minimal glow, flat fills, thin rims, calm nav).
// Non-composable Modifier factories read [LocalUiSkin] via `composed {}`.
// ---------------------------------------------------------------------------

/** True when the active skin is the deep/glowy Galaxy treatment. Nova (flat)
 *  and Classic both return false. Read inside `composed {}` or composables. */
@Composable
fun isGalaxySkin(): Boolean = LocalUiSkin.current == UiSkin.Galaxy

/**
 * NOVA MATERIAL — the shared depth / motion layer for the experimental redesign.
 *
 * Everything here is Nova-only and gated: modifiers are named for the Nova look
 * and are meant to be applied inside `if (LocalExperimentalRedesign.current)`
 * branches (or on surfaces that only exist in the Nova path). None of this
 * touches the classic look.
 */

// ---------------------------------------------------------------------------
// Depth: soft ambient shadow + lit hairline rim, tuned from NovaDepth tokens.
// ---------------------------------------------------------------------------

/**
 * The signature Nova depth: a soft, accent-tinted drop shadow layered under a
 * clipped, gradient-filled surface with a lit hairline rim on top. This is the
 * single biggest jump from "flat recolor" to "premium" — route every Nova card,
 * bar, composer and space card through it so depth is consistent and tunable in
 * one place.
 *
 * @param shape the surface shape (usually a [NovaCorners] value).
 * @param tint base surface fill; the top edge is lifted toward white+accent.
 * @param accent accent used for the rim and (optionally) the shadow spot color.
 * @param accented when true the rim lights up with the accent (active/unread).
 * @param glow when true the drop shadow is tinted with the accent (a lit halo);
 *   otherwise a neutral ambient shadow. Keep glow for floating/hero surfaces.
 * @param elevation shadow depth; defaults to [NovaDepth.cardElevation].
 */
fun Modifier.novaElevation(
    shape: Shape,
    tint: Color,
    accent: Color,
    accented: Boolean = false,
    glow: Boolean = false,
    elevation: Dp = NovaDepth.cardElevation,
): Modifier = composed {
    if (isGalaxySkin()) {
        // GALAXY — deep: soft accent-tinted drop shadow + lifted-top gradient +
        // lit hairline rim.
        this
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = if (glow) accent.copy(alpha = NovaDepth.glowStrong) else Color.Black.copy(alpha = NovaDepth.glowSoft),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(lerp(tint, lerp(Color.White, accent, 0.4f), NovaDepth.highlightTop), tint),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = NovaDepth.rimTop),
                        if (accented) accent.copy(alpha = NovaDepth.rimAccent) else accent.copy(alpha = NovaDepth.rimBottom),
                    ),
                ),
                shape,
            )
    } else {
        // NOVA — flat: NO drop shadow at all (matches the exp2 reference, whose
        // cards never cast). Just a flat solid `surfaceContainer` fill and a thin
        // single-color 1dp hairline rim. Reads clean/paper, never lit or floating.
        this
            .clip(shape)
            .background(tint)
            .border(
                1.dp,
                if (accented) accent.copy(alpha = NovaFlat.rimAccent) else Color.White.copy(alpha = NovaFlat.rim),
                shape,
            )
    }
}

/**
 * Fill-only Nova depth (no drop shadow): the lifted-top gradient + lit rim, for
 * surfaces that live inside a scrolling list where a real shadow per row would
 * be wasteful. This is what `glassCard`'s Nova branch uses.
 */
fun Modifier.novaSurface(
    shape: Shape,
    tint: Color,
    accent: Color,
    accented: Boolean = false,
): Modifier = composed {
    if (isGalaxySkin()) {
        // GALAXY — lifted-top gradient fill + lit rim.
        this
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(lerp(tint, lerp(Color.White, accent, 0.4f), NovaDepth.highlightTop), tint),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = NovaDepth.rimTop),
                        if (accented) accent.copy(alpha = NovaDepth.rimAccent) else accent.copy(alpha = NovaDepth.rimBottom),
                    ),
                ),
                shape,
            )
    } else {
        // NOVA — flat solid fill + a thin single-color rim (no gradient lift).
        this
            .clip(shape)
            .background(tint)
            .border(
                1.dp,
                if (accented) accent.copy(alpha = NovaFlat.rimAccent) else Color.White.copy(alpha = NovaFlat.rim),
                shape,
            )
    }
}

/**
 * Animated accent rim that lights up on a state change (selection, unread,
 * focus). Border-alpha only — no blur — so it's LazyColumn-safe. Layer it over
 * a surface that already has a fill; pass [active] to drive the glow.
 */
fun Modifier.novaGlow(
    shape: Shape,
    accent: Color,
    active: Boolean,
    idleAlpha: Float = NovaDepth.rimBottom,
    activeAlpha: Float = NovaDepth.rimAccent,
): Modifier = composed {
    if (!isGalaxySkin()) {
        // NOVA (flat): no animated glow. A thin static hairline that only steps
        // to the (dimmer, flat) accent alpha when active — no bloom, no spring.
        return@composed border(
            1.dp,
            if (active) accent.copy(alpha = NovaFlat.rimAccent) else Color.White.copy(alpha = NovaFlat.rim),
            shape,
        )
    }
    val a by animateColorAsState(
        targetValue = accent.copy(alpha = if (active) activeAlpha else idleAlpha),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "novaGlow",
    )
    border(1.dp, a, shape)
}

// ---------------------------------------------------------------------------
// Aurora background: 2-3 large low-alpha radial blobs over the Void, with an
// optional slow ambient drift. Draw once behind non-scrolling content.
// ---------------------------------------------------------------------------

/**
 * Gradient-mesh / aurora canvas for the Nova app. Draws large, very-low-alpha
 * radial gradients (iris top-left, cyan bottom-right) over the current fill —
 * the "space-indigo canvas" the brief calls for. Effectively free: pure draw,
 * no per-row cost. Apply behind whole screens or non-scrolling heroes.
 *
 * @param accent the iris blob color (tracks the active accent).
 * @param animate slow ambient drift; automatically snaps off under reduced
 *   motion. Only enable on non-scrolling surfaces (heroes, onboarding, shells).
 */
fun Modifier.novaAuroraBackground(
    accent: Color,
    cyan: Color = NovaColors.Cyan,
    animate: Boolean = false,
): Modifier = composed {
    val galaxy = isGalaxySkin()
    // NOVA (flat): the aurora is OFF entirely — no radial blobs, no drift, no
    // deep-space mesh. This is the single biggest Nova↔Galaxy differentiator: the
    // Nova canvas is a PLAIN SOLID theme background. Paint the flat `surface`
    // fill so callers that use aurora as their only background still get a solid
    // canvas (never transparent) — just with zero animated/glowy space mesh.
    if (!galaxy) return@composed background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
    val reduced = LocalReducedMotion.current
    val drift = if (animate && !reduced) {
        val t = rememberInfiniteTransition(label = "aurora")
        val v by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(26000, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "auroraDrift",
        )
        v
    } else 0.5f
    // GALAXY only: the full gradient-mesh aurora.
    val irisA = 0.11f
    val cyanA = 0.075f
    drawBehind {
        val w = size.width
        val h = size.height
        // iris blob, top-left, breathing slightly toward center
        val irisC = Offset(w * (0.16f + 0.06f * drift), h * (0.10f + 0.05f * drift))
        val irisR = size.maxDimension * (0.85f + 0.08f * drift)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = irisA), Color.Transparent),
                center = irisC,
                radius = irisR,
            ),
            radius = irisR,
            center = irisC,
        )
        // cyan blob, bottom-right, counter-drifting
        val cyanC = Offset(w * (0.88f - 0.06f * drift), h * (0.92f - 0.05f * drift))
        val cyanR = size.maxDimension * (0.75f + 0.08f * (1f - drift))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(cyan.copy(alpha = cyanA), Color.Transparent),
                center = cyanC,
                radius = cyanR,
            ),
            radius = cyanR,
            center = cyanC,
        )
    }
}

/** A soft accent halo washed *under* a surface's content — used behind hero
 *  glyphs, active pills and empty-state icons to make them lift off the fold.
 *  Cheap radial draw; pair with a clip if you want it contained. */
fun Modifier.novaHalo(accent: Color, alpha: Float = NovaDepth.haloAlpha, center: Offset? = null): Modifier = composed {
    // NOVA (flat): the accent halo is a no-op — the flat skin has no glow under
    // glyphs/pills/icons. GALAXY keeps the full radial wash.
    if (!isGalaxySkin()) return@composed this
    val a = alpha
    drawBehind {
        val c = center ?: Offset(size.width / 2f, size.height / 2f)
        val r = size.maxDimension * 0.75f
        drawCircle(
            brush = Brush.radialGradient(listOf(accent.copy(alpha = a), Color.Transparent), center = c, radius = r),
            radius = r,
            center = c,
        )
    }
}

// ---------------------------------------------------------------------------
// Motion: named entrance / interaction specs so Nova pulls from one physics
// library instead of every surface improvising tween(160)/spring(0.82).
// ---------------------------------------------------------------------------

/** Nova's extended motion vocabulary — layered on top of [PigeonMotion].
 *  All specs snap under reduced motion. */
object NovaMotion {
    /** Bold overshoot for celebratory pops (send, reaction appear). */
    @Composable
    fun <T> pop(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)

    /** Emphasized settle for hero reveals and large layout shifts. */
    @Composable
    fun <T> emphasized(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    /** Quick tactile spring for press / selection states. */
    @Composable
    fun <T> press(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * One-shot coordinated reveal for Nova hero blocks / big display titles: a fade
 * + upward rise + faint scale on first composition. Snaps under reduced motion.
 * Apply to the big title so entering any Nova screen has a designed reveal
 * instead of static text popping in. Optional [delayMillis] to stagger blocks.
 */
fun Modifier.heroAppear(delayMillis: Int = 0): Modifier = composed {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis.toLong())
        shown = true
    }
    val p by animateFloatAsState(
        targetValue = if (shown || reduced) 1f else 0f,
        animationSpec = NovaMotion.emphasized(),
        label = "heroAppear",
    )
    graphicsLayer {
        alpha = p
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * 22.dp.toPx()
    }
}

/** Slow, reduced-motion-aware breathing pulse (0..1) for living presence cues —
 *  online rings, empty-state glows. Returns a constant mid value when motion is
 *  reduced so callers can multiply it in without branching. */
@Composable
fun rememberNovaPulse(periodMillis: Int = 2600): Float {
    val reduced = LocalReducedMotion.current
    if (reduced) return 0.5f
    val t = rememberInfiniteTransition(label = "novaPulse")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "novaPulseV",
    )
    return v
}
