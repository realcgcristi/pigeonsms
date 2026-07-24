package app.pigeonsms.ui.util

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.novaSurface
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Liquid Glass surface: a frosted translucent fill with a bright top edge
 * highlight and a soft inner sheen. Apply to cards, bars, headers when the
 * experimental glass look is enabled. Real backdrop blur is layered separately
 * (haze) on the nav pill; this gives the material + edge light everywhere else.
 *
 * Deliberately CHEAP: pure clip + gradient fill + static sheen + gradient
 * border. No tilt state reads, no RenderEffect — safe to apply per-row inside
 * a LazyColumn. Pass [accent] for a faint accent-tinted vibrancy glow.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color,
    highlight: Color = Color.White.copy(alpha = 0.40f),
    accent: Color = Color.Unspecified,
): Modifier = this
    .clip(shape)
    .background(
        // lower-opacity fill: glass should feel thin, not milky
        Brush.verticalGradient(
            listOf(tint.copy(alpha = 0.42f), tint.copy(alpha = 0.22f)),
        ),
    )
    .drawBehind {
        // static specular sheen along the top edge — drawn once, never animates
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.06f),
                0.30f to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
        // soft inner shadow pooling at the bottom edge — gives the slab weight
        drawRect(
            brush = Brush.verticalGradient(
                0.72f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.08f),
            ),
        )
        if (accent.isSpecified) {
            // accent vibrancy: a restrained glow in the bottom-trailing corner
            val r = size.maxDimension * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 1.05f),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width * 0.92f, size.height * 1.05f),
            )
        }
    }
    .border(
        1.dp,
        // 1dp rim light: white top-left fading out diagonally, with only a
        // whisper of accent at the bottom-right — physical, not neon
        Brush.linearGradient(
            listOf(
                highlight.copy(alpha = highlight.alpha * 0.40f),
                highlight.copy(alpha = highlight.alpha * 0.10f),
                if (accent.isSpecified) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.03f),
            ),
        ),
        shape,
    )

/**
 * The everyday card material: liquid glass when the glass look is on, a plain
 * tonal surface otherwise. Reads the theme so callers stay one-liners. Uses the
 * cheap [liquidGlass] variant, so it's safe on LazyColumn rows.
 */
@Composable
fun Modifier.glassCard(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    accented: Boolean = false,
): Modifier =
    if (app.pigeonsms.design.theme.LocalLiquidGlass.current) {
        val wallTint = app.pigeonsms.design.theme.LocalGlassTint.current
        liquidGlass(
            shape = shape,
            tint = lerp(tint, wallTint, 0.08f),
            accent = if (accented) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
    } else if (app.pigeonsms.design.theme.LocalExperimentalRedesign.current) {
        // Nova card: lifted-top gradient fill + brighter lit hairline rim, from
        // the central NovaDepth ladder so every Nova surface shares one edge
        // language. Fill-only (no per-row drop shadow) — LazyColumn-safe.
        this.novaSurface(
            shape = shape,
            tint = tint,
            accent = MaterialTheme.colorScheme.primary,
            accented = accented,
        )
    } else {
        this.clip(shape).background(tint)
    }

/**
 * List-row variant: frosted glass card when the glass look is on, plain
 * clipped (transparent) row otherwise — classic mode keeps its airy list look.
 * Cheap on purpose; designed for LazyColumn rows.
 */
@Composable
fun Modifier.glassRow(shape: Shape): Modifier =
    if (app.pigeonsms.design.theme.LocalLiquidGlass.current) {
        val wallTint = app.pigeonsms.design.theme.LocalGlassTint.current
        liquidGlass(
            shape = shape,
            tint = lerp(MaterialTheme.colorScheme.surfaceContainer, wallTint, 0.08f),
            highlight = Color.White.copy(alpha = 0.26f),
        )
    } else {
        this.clip(shape)
    }

/** A glassy specular blob used as a control thumb. Clipped-first (never square
 *  corners) translucent fill with a tilt-tracked specular hotspot drawn inside
 *  the clip and a bright rim. Runs at 120fps — the highlight is drawn, not a
 *  shader, and reads tilt inside the draw pass so it never recomposes. */
@Composable
fun Modifier.liquidGlassThumb(): Modifier {
    val tilt = rememberTilt()
    val glassTint = app.pigeonsms.design.theme.LocalGlassTint.current
    return this
        .clip(CircleShape)
        .background(
            Brush.verticalGradient(listOf(glassTint.copy(alpha = 0.30f), glassTint.copy(alpha = 0.14f))),
        )
        .drawWithContent {
            drawContent()
            val t = tilt.value
            val cx = size.width * (0.5f + t.x * 0.34f)
            val cy = size.height * (0.40f + t.y * 0.34f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.minDimension * 0.75f,
                ),
                radius = size.minDimension * 0.75f,
                center = Offset(cx, cy),
            )
            // grounding shadow inside the lower arc of the thumb
            drawRect(
                brush = Brush.verticalGradient(
                    0.65f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.10f),
                ),
            )
        }
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.15f))), CircleShape)
}

/**
 * Liquid Glass panel — frosted translucent fill with a tilt specular + bright top
 * edge highlight. Clipped-first so it can't paint square corners. For bars,
 * headers, cards, sheets when glass is on. One tilt-driven specular per panel:
 * use sparingly (heroes, sheets, bars) — lazy rows should use [liquidGlass] /
 * [glassCard] instead. Pass [accent] to let the panel pick up an accent glow.
 */
@Composable
fun Modifier.glassPanel(shape: Shape, tint: Color, accent: Color = Color.Unspecified): Modifier {
    val tilt = rememberTilt()
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.50f), tint.copy(alpha = 0.28f))))
        .drawWithContent {
            drawContent()
            val t = tilt.value
            val cx = size.width * (0.5f + t.x * 0.3f)
            // tilt specular, restrained: presence, not glare
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(cx, 0f),
                    radius = size.height * 1.4f,
                ),
                radius = size.height * 1.4f,
                center = Offset(cx, 0f),
            )
            // soft inner shadow settling on the bottom edge
            drawRect(
                brush = Brush.verticalGradient(
                    0.70f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.10f),
                ),
            )
            if (accent.isSpecified) {
                val r = size.maxDimension * 0.8f
                val c = Offset(size.width * (0.16f - t.x * 0.10f), size.height * 1.1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.09f), Color.Transparent),
                        center = c,
                        radius = r,
                    ),
                    radius = r,
                    center = c,
                )
            }
        }
        .border(
            1.dp,
            // rim light enters at the top-left and dies out diagonally
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.05f),
                    if (accent.isSpecified) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.03f),
                ),
            ),
            shape,
        )
}

/**
 * Liquid-glass toggle: a pill track with a glass thumb that slides on a bouncy
 * spring. Drop-in for the M3 Switch when the glass look is on.
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackW = 52.dp
    val trackH = 30.dp
    val pad = 3.dp
    val thumb = trackH - pad * 2
    val onColor = MaterialTheme.colorScheme.primary
    val offColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (checked) onColor else offColor, label = "switchBg")
    val pos by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "switchThumb",
    )
    Box(
        modifier
            .size(trackW, trackH)
            .clip(CircleShape)
            .background(bg)
            .clickable { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onCheckedChange(!checked) }
            .padding(pad),
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = trackW - pad * 2 - thumb
        Box(Modifier.offset(x = travel * pos).size(thumb).liquidGlassThumb())
    }
}

/**
 * Liquid-glass segmented control: the selected glass thumb slides between
 * options on a bouncy spring and stretches as it travels (the elastic "liquid"
 * morph from the adoption guide), then settles.
 */
@Composable
fun LiquidSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    val count = options.size.coerceAtLeast(1)
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)),
    ) {
        val segW = maxWidth / count
        val targetX = segW * selected
        val x by animateDpAsState(
            targetX,
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
            label = "segX",
        )
        var stretch by remember { mutableStateOf(1f) }
        val animStretch by animateFloatAsState(stretch, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "segStretch")
        LaunchedEffect(selected) {
            stretch = 1.22f
            delay(90)
            stretch = 1f
        }
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .padding(3.dp)
                .graphicsLayer {
                    scaleX = animStretch
                    scaleY = 2f - animStretch
                    transformOrigin = TransformOrigin(if (selected == 0) 0.25f else 0.75f, 0.5f)
                }
                .liquidGlassThumb(),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .pointerInput(i) { detectTapGestures { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSelect(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Liquid-glass slider: accent-filled track with a translucent glass thumb that
 * squishes toward the drag direction (anisotropic stretch) and springs back on
 * release — matching the adoption-guide slider.
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val trackOff = MaterialTheme.colorScheme.surfaceContainerHighest
    var stretch by remember { mutableStateOf(1f) }
    val animStretch by animateFloatAsState(stretch, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "sliderStretch")
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val thumb = 28.dp
        val thumbPx = with(density) { thumb.toPx() }
        // guard: layout narrower than the thumb would make this denominator ≤0 → NaN
        val travelPx = (widthPx - thumbPx).coerceAtLeast(1f)
        fun fracFromX(px: Float) = ((px - thumbPx / 2f) / travelPx).coerceIn(0f, 1f)
        fun emit(px: Float) = onValueChange(valueRange.start + fracFromX(px) * span)

        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(CircleShape).background(trackOff))
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(6.dp).clip(CircleShape).background(accent))
        val thumbX = with(density) { (frac * travelPx).toDp() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbX)
                .size(thumb)
                .graphicsLayer { scaleX = animStretch; scaleY = 2f - animStretch }
                .liquidGlassThumb(),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(widthPx) { detectTapGestures { emit(it.x) } }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); emit(it.x) },
                        onDragEnd = { stretch = 1f },
                        onDragCancel = { stretch = 1f },
                    ) { change, drag ->
                        change.consume()
                        stretch = (1f + abs(drag) / 34f).coerceIn(1f, 1.5f)
                        emit(change.position.x)
                    }
                },
        )
    }
}
