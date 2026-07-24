package app.pigeonsms.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 4dp grid. Use these, never raw dp literals, so density stays coherent. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp
}

/** Shared structural sizes — keep list rows and bars on the same rhythm app-wide. */
object Dimens {
    /** Minimum height for tappable list/menu rows. */
    val listRowHeight = 64.dp

    /** Height of screen top bars / sub-screen headers. */
    val topBarHeight = 64.dp

    /** iOS-Settings-style grouped rows: calmer, denser than list rows. */
    val settingsRowHeight = 56.dp

    /** Soft tinted rounded square that holds a leading row icon. */
    val iconBadge = 28.dp

    /** Icon size inside an [iconBadge]. */
    val iconSmall = 20.dp

    /** Minimum touch target for any tappable control. */
    val touchTarget = 48.dp

    /** Full-width primary CTA height. */
    val ctaHeight = 56.dp

    /** Hero avatar on profile screens. */
    val avatarHero = 96.dp
}

/** Corner language: bubbles are soft, sheets are softer, buttons in between. */
object Corners {
    val chip = RoundedCornerShape(10.dp)
    val button = RoundedCornerShape(14.dp)
    val input = RoundedCornerShape(16.dp)
    val bubble = RoundedCornerShape(18.dp)
    val card = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Grouped settings-style container: softer than chip, calmer than card. */
    val group = RoundedCornerShape(20.dp)

    /** The rounded square behind a row's leading icon. */
    val iconBadge = RoundedCornerShape(9.dp)
}
