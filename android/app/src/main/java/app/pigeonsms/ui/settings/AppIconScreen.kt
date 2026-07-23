package app.pigeonsms.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pigeonsms.R
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.novaGlow
import app.pigeonsms.design.theme.novaHalo
import kotlinx.coroutines.launch

internal data class AppIconVariant(val label: String, val key: String, val author: String = "Andrei")

private val ICON_PREVIEW = 44.dp

internal val AppIconVariants = listOf(
    AppIconVariant("Hacker Code", "hackercode", author = "admin"),
    AppIconVariant("Photorealistic", "photorealistic"),
    AppIconVariant("Sunset", "sunset"),
    AppIconVariant("Starry Midnight", "starrymidnight"),
    AppIconVariant("Noir", "noir"),
    AppIconVariant("Neon Vaporwave", "neonvaporwave"),
    AppIconVariant("Minimal White", "minimalwhite"),
    AppIconVariant("Minimal Black Alt", "minimalblackalt"),
    AppIconVariant("Vivid Clean", "vividclean"),
    AppIconVariant("Vivid Classic", "vividclassic"),
    AppIconVariant("Pixel Classic", "pixelclassic"),
    AppIconVariant("Minimal Black", "minimalblack"),
    AppIconVariant("Minecraft", "minecraft", author = "admin"),
    AppIconVariant("Kawaii", "kawaii"),
    AppIconVariant("Hacker Simple", "hackersimple"),
    AppIconVariant("Clean", "clean"),
    AppIconVariant("Classic", "classic"),
    AppIconVariant("Pallete Switch Classic", "paletteclassic"),
    AppIconVariant("Pallete Switch Clean", "paletteclean"),
    AppIconVariant("Saucy Pigeon", "saucy", author = "a_arond"),
)

private fun fgRes(key: String): Int = when (key) {
    "photorealistic" -> R.mipmap.ic_fg_photorealistic
    "sunset" -> R.mipmap.ic_fg_sunset
    "starrymidnight" -> R.mipmap.ic_fg_starrymidnight
    "noir" -> R.mipmap.ic_fg_noir
    "neonvaporwave" -> R.mipmap.ic_fg_neonvaporwave
    "minimalwhite" -> R.mipmap.ic_fg_minimalwhite
    "minimalblackalt" -> R.mipmap.ic_fg_minimalblackalt
    "vividclean" -> R.mipmap.ic_fg_vividclean
    "vividclassic" -> R.mipmap.ic_fg_vividclassic
    "pixelclassic" -> R.mipmap.ic_fg_pixelclassic
    "minimalblack" -> R.mipmap.ic_fg_minimalblack
    "minecraft" -> R.mipmap.ic_fg_minecraft
    "kawaii" -> R.mipmap.ic_fg_kawaii
    "hackercode" -> R.mipmap.ic_fg_hackercode
    "hackersimple" -> R.mipmap.ic_fg_hackersimple
    "clean" -> R.mipmap.ic_fg_clean
    "classic" -> R.mipmap.ic_fg_classic
    "paletteclassic" -> R.mipmap.ic_fg_paletteclassic
    "paletteclean" -> R.mipmap.ic_fg_paletteclean
    "saucy" -> R.mipmap.ic_fg_saucy
    else -> R.mipmap.ic_fg_classic
}

private fun bgRes(key: String): Int = when (key) {
    "photorealistic" -> R.color.ic_bg_photorealistic
    "sunset" -> R.color.ic_bg_sunset
    "starrymidnight" -> R.color.ic_bg_starrymidnight
    "noir" -> R.color.ic_bg_noir
    "neonvaporwave" -> R.color.ic_bg_neonvaporwave
    "minimalwhite" -> R.color.ic_bg_minimalwhite
    "minimalblackalt" -> R.color.ic_bg_minimalblackalt
    "vividclean" -> R.color.ic_bg_vividclean
    "vividclassic" -> R.color.ic_bg_vividclassic
    "pixelclassic" -> R.color.ic_bg_pixelclassic
    "minimalblack" -> R.color.ic_bg_minimalblack
    "minecraft" -> R.color.ic_bg_minecraft
    "kawaii" -> R.color.ic_bg_kawaii
    "hackercode" -> R.color.ic_bg_hackercode
    "hackersimple" -> R.color.ic_bg_hackersimple
    "clean" -> R.color.ic_bg_clean
    "classic" -> R.color.ic_bg_classic
    "paletteclassic" -> R.color.ic_bg_paletteclassic
    "paletteclean" -> R.color.ic_bg_paletteclean
    "saucy" -> R.color.ic_bg_saucy
    else -> R.color.ic_bg_classic
}

internal fun setAppIcon(context: android.content.Context, key: String) {
    val pm = context.packageManager
    AppIconVariants.forEach { v ->
        pm.setComponentEnabledSetting(
            android.content.ComponentName(context, "app.pigeonsms.Icon_${v.key}"),
            if (v.key == key) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
    }
}

internal fun currentAppIcon(context: android.content.Context): String {
    val pm = context.packageManager
    return AppIconVariants.firstOrNull { v ->
        pm.getComponentEnabledSetting(android.content.ComponentName(context, "app.pigeonsms.Icon_${v.key}")) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }?.key ?: "default"
}

@Composable
fun AppIconScreen(onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(currentAppIcon(context)) }
    val social = remember {
        (context.applicationContext as app.pigeonsms.PigeonApp).container.socialRepository
    }
    val scope = rememberCoroutineScope()
    val openUserByName: (String) -> Unit = { username ->
        scope.launch {
            runCatching { social.searchUsers(username) }.getOrNull()
                ?.firstOrNull { it.username.equals(username, ignoreCase = true) }
                ?.let { onOpenUser(it.id) }
        }
    }
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l)) {
        SettingsSubHeader("app icon", onBack)

        val nova = LocalExperimentalRedesign.current
        GroupCard {
            AppIconVariants.forEachIndexed { i, v ->
                if (i > 0) IconRowDivider()
                val selected = current == v.key

                // an icon reads as a physical state-change, not a static tick.
                val rowMod = if (nova) {
                    Modifier.novaGlow(NovaCorners.card, accent, active = selected)
                } else Modifier
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(if (nova) NovaCorners.card else Corners.card)
                        .then(rowMod)
                        .clickable {
                            setAppIcon(context, v.key)
                            current = v.key
                        }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .then(if (nova && selected) Modifier.novaHalo(accent, alpha = NovaDepth.haloAlpha) else Modifier)
                            .size(ICON_PREVIEW).clip(if (nova) NovaCorners.iconBadge else Corners.card)
                            .background(colorResource(bgRes(v.key)))
                            .then(if (nova) Modifier.border(1.dp, accent.copy(alpha = if (selected) NovaDepth.rimAccent else NovaDepth.rimBottom), NovaCorners.iconBadge) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {

                        // foreground for the launcher mask; in this flat preview
                        // that reads as a shrunk framed photo, so scale to fill and
                        // let the clip crop the border — matching the launcher look.
                        val fullBleedPreview = v.key in setOf(
                            "kawaii", "noir", "neonvaporwave", "starrymidnight",
                            "sunset", "photorealistic", "hackercode", "minecraft",
                        )
                        Image(
                            painterResource(fgRes(v.key)),
                            contentDescription = null,
                            modifier = Modifier.size(ICON_PREVIEW)
                                .then(if (fullBleedPreview) Modifier.graphicsLayer { scaleX = 1.5f; scaleY = 1.5f } else Modifier),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                        Text(
                            v.label,
                            style = if (nova) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val creditText = buildAnnotatedString {
                            append("made by ")
                            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append("@${v.author}") }
                        }
                        Text(
                            creditText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { openUserByName(v.author) },
                        )
                    }
                    if (nova) {
                        // spring pop-in check inside a soft cyan-accented disc
                        AnimatedVisibility(visible = selected, enter = fadeIn() + scaleIn(NovaMotion.pop())) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(accent, NovaColors.Cyan))),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Check, "selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = "selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimens.iconSmall),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.huge))
    }
}

@Composable
private fun IconRowDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = Spacing.l + ICON_PREVIEW + Spacing.m),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}
