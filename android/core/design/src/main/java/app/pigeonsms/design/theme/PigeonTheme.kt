package app.pigeonsms.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

enum class PigeonThemeMode { System, Dark, Oled, Light }

/**
 * The chosen UI skin. Classic = the untouched original look. Nova and Galaxy
 * both render the experimental LAYOUTS (screens branch on
 * [LocalExperimentalRedesign], which is true for both) and differ only in the
 * design-system TREATMENT:
 *  - [Nova]   — flatter, cleaner: minimal glow, flat cards + thin rim, calm nav.
 *  - [Galaxy] — deep/glowy: gradient-mesh aurora, accent glow, big soft shadows.
 */
enum class UiSkin { Classic, Nova, Galaxy }

/** The active skin, read by the Nova design primitives to pick flat (Nova) vs
 *  deep (Galaxy) treatment. Defaults to Classic. */
val LocalUiSkin = staticCompositionLocalOf { UiSkin.Classic }

/** Whether motion should be minimized (accessibility setting), read anywhere. */
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalAccent = staticCompositionLocalOf { accentByKey("peach") }

/** Experimental Liquid Glass look — frosted translucent surfaces, edge highlights,
 *  liquid-morphing controls. Read anywhere to opt a surface into the glass style. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** Whole-app experimental redesign ("Nova"): vivid indigo palette, rounder
 *  shapes, louder type, a reimagined floating nav. Read anywhere to branch a
 *  surface onto the Nova treatment; off = classic look, untouched. */
val LocalExperimentalRedesign = staticCompositionLocalOf { false }

/** Tint the glass picks up from the active wallpaper (white when none). */
val LocalGlassTint = staticCompositionLocalOf { androidx.compose.ui.graphics.Color.White }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PigeonTheme(
    mode: PigeonThemeMode = PigeonThemeMode.Dark,
    accentKey: String = "peach",
    reducedMotion: Boolean = false,
    liquidGlass: Boolean = false,
    glassTint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
    dynamicColor: Boolean = false,
    skin: UiSkin = UiSkin.Classic,
    content: @Composable () -> Unit,
) {
    // Both Nova and Galaxy render the experimental layouts; Classic is untouched.
    val experimental = skin != UiSkin.Classic
    // In experimental (Nova) mode the default accent is Iris; a user-chosen
    // accent still wins so people keep their pick across the redesign.
    val accent =
        if (experimental && accentKey == "peach") NovaAccent else accentByKey(accentKey)
    val dark = when (mode) {
        PigeonThemeMode.Dark, PigeonThemeMode.Oled -> true
        PigeonThemeMode.Light -> false
        PigeonThemeMode.System -> isSystemInDarkTheme()
    }
    val oled = mode == PigeonThemeMode.Oled

    // Material You: palette derived from the device wallpaper (API 31+), opt-in
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = if (dynamicColor && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    } else if (experimental) {
        if (dark) novaDarkScheme(accent, oled) else novaLightScheme(accent)
    } else if (dark) {
        darkColorScheme(
            primary = accent.bright,
            onPrimary = accent.on,
            primaryContainer = accent.deep,
            onPrimaryContainer = accent.on,
            secondary = PigeonColors.Lavender,
            onSecondary = PigeonColors.OnLavender,
            background = if (oled) PigeonColors.InkOled else PigeonColors.Ink,
            onBackground = PigeonColors.TextPrimary,
            surface = if (oled) PigeonColors.InkOled else PigeonColors.Ink,
            onSurface = PigeonColors.TextPrimary,
            surfaceContainer = if (oled) PigeonColors.SurfaceOled else PigeonColors.Surface,
            surfaceContainerHigh = if (oled) PigeonColors.Surface else PigeonColors.SurfaceHigh,
            surfaceContainerHighest = if (oled) PigeonColors.SurfaceHigh else PigeonColors.SurfaceHighest,
            surfaceVariant = PigeonColors.SurfaceHigh,
            onSurfaceVariant = PigeonColors.TextSecondary,
            outline = PigeonColors.Outline,
            error = PigeonColors.Danger,
        )
    } else {
        lightColorScheme(
            primary = accent.deep,
            onPrimary = PigeonColors.Paper,
            primaryContainer = accent.bright,
            onPrimaryContainer = accent.on,
            secondary = PigeonColors.Lavender,
            onSecondary = PigeonColors.OnLavender,
            background = PigeonColors.Paper,
            onBackground = PigeonColors.InkOnPaper,
            surface = PigeonColors.Paper,
            onSurface = PigeonColors.InkOnPaper,
            surfaceContainer = PigeonColors.PaperSurface,
            surfaceContainerHigh = PigeonColors.PaperSurfaceHigh,
            surfaceContainerHighest = PigeonColors.PaperSurfaceHigh,
            surfaceVariant = PigeonColors.PaperSurfaceHigh,
            onSurfaceVariant = PigeonColors.InkOnPaperSecondary,
            outline = PigeonColors.PaperOutline,
            error = PigeonColors.Danger,
        )
    }

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalAccent provides accent,
        LocalLiquidGlass provides liquidGlass,
        LocalGlassTint provides glassTint,
        LocalUiSkin provides skin,
        LocalExperimentalRedesign provides experimental,
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = if (reducedMotion) MotionScheme.standard() else MotionScheme.expressive(),
            typography = if (experimental) NovaTypography else PigeonTypography,
            shapes = if (experimental) NovaShapes else androidx.compose.material3.Shapes(),
            content = content,
        )
    }
}
