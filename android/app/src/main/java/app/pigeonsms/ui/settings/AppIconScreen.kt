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
import app.pigeonsms.design.theme.Spacing
import kotlinx.coroutines.launch

internal data class AppIconVariant(val label: String, val key: String)

private val ICON_PREVIEW = 44.dp

internal val AppIconVariants = listOf(
    AppIconVariant("Minimal White", "minwhite"),
    AppIconVariant("Minimal black alternative", "minblackalt"),
    AppIconVariant("Clean", "clean"),
    AppIconVariant("Clean alternative", "cleanalt"),
    AppIconVariant("Default", "default"),
    AppIconVariant("Minimal Black", "minblack"),
    AppIconVariant("Alternative", "alternative"),
    AppIconVariant("Retro/8-bit", "retro"),
)

private fun fgRes(key: String): Int = when (key) {
    "minwhite" -> R.mipmap.ic_fg_minwhite
    "minblackalt" -> R.mipmap.ic_fg_minblackalt
    "clean" -> R.mipmap.ic_fg_clean
    "cleanalt" -> R.mipmap.ic_fg_cleanalt
    "default" -> R.mipmap.ic_fg_default
    "minblack" -> R.mipmap.ic_fg_minblack
    "alternative" -> R.mipmap.ic_fg_alternative
    "retro" -> R.mipmap.ic_fg_retro
    else -> R.mipmap.ic_fg_default
}

private fun bgRes(key: String): Int = when (key) {
    "minwhite" -> R.color.ic_bg_minwhite
    "minblackalt" -> R.color.ic_bg_minblackalt
    "clean" -> R.color.ic_bg_clean
    "cleanalt" -> R.color.ic_bg_cleanalt
    "default" -> R.color.ic_bg_default
    "minblack" -> R.color.ic_bg_minblack
    "alternative" -> R.color.ic_bg_alternative
    "retro" -> R.color.ic_bg_retro
    else -> R.color.ic_bg_default
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
    val openAndrei: () -> Unit = {
        scope.launch {
            runCatching { social.searchUsers("Andrei") }.getOrNull()
                ?.firstOrNull { it.username.equals("Andrei", ignoreCase = true) }
                ?.let { onOpenUser(it.id) }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val creditText = buildAnnotatedString {
        append("made by ")
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append("@Andrei") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l)) {
        SettingsSubHeader("app icon", onBack)

        GroupCard {
            AppIconVariants.forEachIndexed { i, v ->
                if (i > 0) IconRowDivider()
                val selected = current == v.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            setAppIcon(context, v.key)
                            current = v.key
                        }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(ICON_PREVIEW).clip(Corners.card)
                            .background(colorResource(bgRes(v.key))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(fgRes(v.key)),
                            contentDescription = null,
                            modifier = Modifier.size(ICON_PREVIEW),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                        Text(
                            v.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            creditText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(onClick = openAndrei),
                        )
                    }
                    if (selected) {
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
