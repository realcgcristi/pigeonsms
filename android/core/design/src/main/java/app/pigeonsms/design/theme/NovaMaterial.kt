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

// flatter, cleaner treatment (minimal glow, flat fills, thin rims, calm nav).

// ---------------------------------------------------------------------------

@Composable
fun isGalaxySkin(): Boolean = LocalUiSkin.current == UiSkin.Galaxy

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

fun Modifier.novaElevation(
    shape: Shape,
    tint: Color,
    accent: Color,
    accented: Boolean = false,
    glow: Boolean = false,
    elevation: Dp = NovaDepth.cardElevation,
): Modifier = composed {
    if (isGalaxySkin()) {

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

fun Modifier.novaSurface(
    shape: Shape,
    tint: Color,
    accent: Color,
    accented: Boolean = false,
): Modifier = composed {
    if (isGalaxySkin()) {

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

fun Modifier.novaGlow(
    shape: Shape,
    accent: Color,
    active: Boolean,
    idleAlpha: Float = NovaDepth.rimBottom,
    activeAlpha: Float = NovaDepth.rimAccent,
): Modifier = composed {
    if (!isGalaxySkin()) {

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

// ---------------------------------------------------------------------------

fun Modifier.novaAuroraBackground(
    accent: Color,
    cyan: Color = NovaColors.Cyan,
    animate: Boolean = false,
): Modifier = composed {
    val galaxy = isGalaxySkin()

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

fun Modifier.novaHalo(accent: Color, alpha: Float = NovaDepth.haloAlpha, center: Offset? = null): Modifier = composed {

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

// library instead of every surface improvising tween(160)/spring(0.82).
// ---------------------------------------------------------------------------

object NovaMotion {

    @Composable
    fun <T> pop(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)

    @Composable
    fun <T> emphasized(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    @Composable
    fun <T> press(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
}

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
