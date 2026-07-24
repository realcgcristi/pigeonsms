package app.pigeonsms.design.theme

import androidx.compose.ui.graphics.Color

/**
 * roost palette — warm plum-charcoal darks with a peach signature accent.
 * Dark is the default personality; oled swaps the floor to true black.
 */
object PigeonColors {
    // canvas
    val Ink = Color(0xFF16131A)
    val Surface = Color(0xFF1D1922)
    val SurfaceHigh = Color(0xFF262130)
    val SurfaceHighest = Color(0xFF2F2939)
    val Outline = Color(0xFF3A3346)

    // oled floor
    val InkOled = Color(0xFF000000)
    val SurfaceOled = Color(0xFF121016)

    // text
    val TextPrimary = Color(0xFFF2EDF4)
    val TextSecondary = Color(0xFFA99FB3)
    val TextTertiary = Color(0xFF6F6579)

    // brand
    val Peach = Color(0xFFFF9D76)
    val PeachDeep = Color(0xFFE87F55)
    val OnPeach = Color(0xFF2A150C)
    val Lavender = Color(0xFFB8A7F5)
    val OnLavender = Color(0xFF1B1230)

    // status
    val Mint = Color(0xFF7FD8A4)
    val Danger = Color(0xFFFF6B81)
    val Amber = Color(0xFFFFC46B)

    // fallback avatar discs — single source of truth; hash a name into this list
    val AvatarPalette = listOf(
        Peach, Lavender, Mint,
        Color(0xFF76BEFF), Color(0xFFFF8FB0), Amber,
    )

    /** Ink used for the initial letter on [AvatarPalette] discs. */
    val OnAvatar = Color(0xFF201018)

    // light theme canvas
    val Paper = Color(0xFFFAF7F4)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperSurfaceHigh = Color(0xFFF1ECE7)
    val PaperOutline = Color(0xFFDDD4CC)
    val InkOnPaper = Color(0xFF241F29)
    val InkOnPaperSecondary = Color(0xFF6E6577)
}
