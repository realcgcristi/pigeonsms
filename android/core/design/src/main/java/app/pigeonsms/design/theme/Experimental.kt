package app.pigeonsms.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "NOVA" — the experimental redesign identity.
 *
 * A vivid, near-neon electric-violet + cyan story on deep space-indigo surfaces.
 * Where the classic "roost" look is warm plum + peach and cozy, Nova is cool,
 * high-contrast and confident: inky indigo canvas, generous rounded shapes,
 * a punchy iris/cyan accent pair, and a tighter, louder display type ramp.
 *
 * Everything here is self-contained so the classic path is never touched.
 */
object NovaColors {
    // deep space-indigo canvas (dark is the hero personality). A wider tonal
    // ladder than before so surfaces can layer real elevation on the Void.
    val Void = Color(0xFF0B0A18)
    val VoidOled = Color(0xFF000000)
    val Surface = Color(0xFF15132A)
    val SurfaceHigh = Color(0xFF1E1B3A)
    val SurfaceHighest = Color(0xFF272249)
    val Outline = Color(0xFF3B355F)

    // signature accents — electric iris with a cyan counterpoint
    val Iris = Color(0xFF9B8CFF)
    val IrisBright = Color(0xFFB2A6FF) // lifted iris for gradient tops / glows
    val IrisDeep = Color(0xFF6D5CF0)
    val OnIris = Color(0xFF0B0620)
    val Cyan = Color(0xFF5EE7E0)
    val CyanDeep = Color(0xFF2FB9B2) // cyan's deep counterpart for gradients
    val OnCyan = Color(0xFF042220)

    /** A cyan-shifted deep violet used as the far stop of the outgoing bubble /
     *  CTA diagonal so self-surfaces read dimensional instead of mono-violet. */
    val IrisCyanDeep = Color(0xFF4B63C8)

    // text
    val TextPrimary = Color(0xFFF3F1FF)
    val TextSecondary = Color(0xFFB4AEDA)
    val TextTertiary = Color(0xFF746D9E)

    val Danger = Color(0xFFFF6484)

    // light "aurora" canvas — cool paper with a violet cast
    val Paper = Color(0xFFF4F2FF)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperSurfaceHigh = Color(0xFFEAE6FF)
    val PaperOutline = Color(0xFFD5CFF2)
    val InkOnPaper = Color(0xFF17132E)
    val InkOnPaperSecondary = Color(0xFF5B5486)
}

fun novaDarkScheme(accent: Accent, oled: Boolean) = darkColorScheme(
    primary = accent.bright,
    onPrimary = accent.on,
    primaryContainer = accent.deep,
    onPrimaryContainer = NovaColors.TextPrimary,
    secondary = NovaColors.Cyan,
    onSecondary = NovaColors.OnCyan,
    tertiary = NovaColors.Iris,
    onTertiary = NovaColors.OnIris,
    background = if (oled) NovaColors.VoidOled else NovaColors.Void,
    onBackground = NovaColors.TextPrimary,
    surface = if (oled) NovaColors.VoidOled else NovaColors.Void,
    onSurface = NovaColors.TextPrimary,
    surfaceContainerLowest = if (oled) NovaColors.VoidOled else NovaColors.Void,
    surfaceContainer = if (oled) NovaColors.Surface else NovaColors.Surface,
    surfaceContainerHigh = if (oled) NovaColors.SurfaceHigh else NovaColors.SurfaceHigh,
    surfaceContainerHighest = NovaColors.SurfaceHighest,
    surfaceVariant = NovaColors.SurfaceHigh,
    onSurfaceVariant = NovaColors.TextSecondary,
    outline = NovaColors.Outline,
    outlineVariant = NovaColors.Outline,
    error = NovaColors.Danger,
)

fun novaLightScheme(accent: Accent) = lightColorScheme(
    primary = accent.deep,
    onPrimary = Color.White,
    primaryContainer = accent.bright,
    onPrimaryContainer = accent.on,
    secondary = Color(0xFF13A79E),
    onSecondary = Color.White,
    tertiary = NovaColors.IrisDeep,
    onTertiary = Color.White,
    background = NovaColors.Paper,
    onBackground = NovaColors.InkOnPaper,
    surface = NovaColors.Paper,
    onSurface = NovaColors.InkOnPaper,
    surfaceContainer = NovaColors.PaperSurface,
    surfaceContainerHigh = NovaColors.PaperSurfaceHigh,
    surfaceContainerHighest = NovaColors.PaperSurfaceHigh,
    surfaceVariant = NovaColors.PaperSurfaceHigh,
    onSurfaceVariant = NovaColors.InkOnPaperSecondary,
    outline = NovaColors.PaperOutline,
    outlineVariant = NovaColors.PaperOutline,
    error = NovaColors.Danger,
)

/** More expressive, rounder shape scale — feeds MaterialTheme.shapes so any
 *  component reading M3 shapes (buttons, dialogs, menus, cards) picks it up. */
val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** Nova corner language — bolder radii than classic [Corners]. Shared components
 *  branch onto these via [LocalExperimentalRedesign]. */
object NovaCorners {
    val chip = RoundedCornerShape(14.dp)
    val button = RoundedCornerShape(20.dp)
    val input = RoundedCornerShape(22.dp)
    val bubble = RoundedCornerShape(26.dp)
    val card = RoundedCornerShape(30.dp)
    val group = RoundedCornerShape(28.dp)
    val sheet = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    val iconBadge = RoundedCornerShape(13.dp)
}

/**
 * Nova type ramp — louder, tighter display/title scale with negative tracking;
 * bodies keep readable line-height. Distinct silhouette from classic Figtree
 * scale while reusing the same self-hosted family (no new font asset).
 */
val NovaTypography = Typography(
    // hero end of the ramp — loud, tightly tracked. Onboarding & Nova heroes
    // read displayLarge/displayMedium so they stop hand-rolling .copy(fontSize=…).
    displayLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-1.6).sp),
    displayMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-1.3).sp),
    displaySmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.0).sp),
    headlineMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
)

/** The Nova signature accent, used when experimental mode is on and the user
 *  hasn't picked a custom accent. Iris electric-violet. */
val NovaAccent = Accent("iris", "iris", Color(0xFFB388FF), Color(0xFF8B5CF6), Color(0xFF150A2E))

/**
 * Central depth scale for the Nova material. Every card / bar / composer / chip
 * pulls highlight, rim and glow intensities from here so the "lit edge + soft
 * shadow" language is one consistent, tunable ladder instead of ~8 files of
 * scattered magic alphas. Import via [app.pigeonsms.design.theme.NovaDepth].
 */
object NovaDepth {
    // drop-shadow elevations (used by Modifier.novaElevation)
    val cardElevation = 10.dp
    val raisedElevation = 14.dp
    val floatingElevation = 20.dp

    // rim / highlight ladder — perceptible on the Void and OLED black
    const val rimTop = 0.13f        // white top-left hairline on unaccented surfaces
    const val rimBottom = 0.10f     // faint accent at the bottom-right of the rim
    const val rimAccent = 0.30f     // accent rim when a surface is "accented"/active
    const val highlightTop = 0.12f  // top-of-card white lift mixed into the fill

    // ambient accent glow strength for spotColor / halos
    const val glowSoft = 0.22f
    const val glowStrong = 0.34f
    const val haloAlpha = 0.16f     // radial accent halo washed under a surface
}

/**
 * Flat-skin (NOVA) counterpart to [NovaDepth]. When the active skin is Nova the
 * shared primitives read these instead: no accent glow, a whisper of neutral
 * shadow, thin single-color rims, and dimmed halos. Galaxy keeps [NovaDepth].
 */
object NovaFlat {
    const val elevationScale = 0.35f  // drop-shadow depth vs the Galaxy elevation
    const val shadowAlpha = 0.14f     // neutral (never accent) ambient shadow
    const val rim = 0.08f             // thin neutral white hairline rim
    const val rimAccent = 0.22f       // rim when a surface is accented/active
    const val haloScale = 0.35f       // how far novaHalo is dimmed under Nova
}

/**
 * Named brand gradients so accents stop drifting between violet-only and
 * violet+cyan per screen. `cta` and `selfBubble` are diagonal iris→cyan so the
 * dual-accent identity is legible; `heroWash` is a soft top-down accent bath.
 * All take the live accent so a user's custom accent is honoured.
 */
object NovaGradients {
    /** Primary CTA / send-armed / active-pill fill: diagonal iris → cyan-deep. */
    fun cta(accent: Color): List<Color> = listOf(accent, NovaColors.IrisCyanDeep, NovaColors.CyanDeep)

    /** Outgoing chat bubble: accent → cyan-shifted deep, dimensional not flat. */
    fun selfBubble(accent: Color): List<Color> = listOf(accent, NovaColors.IrisCyanDeep)

    /** Hero / cover wash: a low, wide accent bath fading to transparent. */
    fun heroWash(accent: Color): List<Color> =
        listOf(accent.copy(alpha = 0.26f), NovaColors.Cyan.copy(alpha = 0.10f), Color.Transparent)

    /** Empty cover-band mesh: iris → cyan so icon-less spaces still look rich. */
    fun coverMesh(accent: Color): List<Color> =
        listOf(accent.copy(alpha = 0.32f), NovaColors.Cyan.copy(alpha = 0.18f))
}
