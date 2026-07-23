package app.pigeonsms.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.pigeonsms.data.ThemeMode
import app.pigeonsms.data.ThemePrefs
import app.pigeonsms.design.components.NovaHero
import app.pigeonsms.design.components.NovaIconBadgeButton
import app.pigeonsms.design.components.NovaSectionLabel
import app.pigeonsms.design.components.NovaTag
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.PigeonAccents
import app.pigeonsms.design.theme.PigeonWallpapers
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.heroAppear
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.ui.util.LiquidSegmented
import app.pigeonsms.ui.util.LiquidSlider
import app.pigeonsms.ui.util.LiquidSwitch
import app.pigeonsms.ui.util.ScreenHeader
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.glassCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    username: String,
    displayName: String,
    avatarModel: Any?,
    statusText: String?,
    onSessions: () -> Unit,
    onHistory: () -> Unit,
    onSecurity: () -> Unit,
    onEditProfile: () -> Unit,
    onBlocked: () -> Unit,
    onAppearance: () -> Unit,
    onPrivacy: () -> Unit,
    onNotifications: () -> Unit,
    onNests: () -> Unit,
    onSignOut: () -> Unit,
) {
    val skin = LocalUiSkin.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (skin == UiSkin.Galaxy) NovaHero(title = "you") else ScreenHeader("you")
        Column(Modifier.padding(horizontal = Spacing.l)) {
            if (skin == UiSkin.Galaxy) {
                NovaIdentityHero(
                    username = username,
                    displayName = displayName,
                    avatarModel = avatarModel,
                    statusText = statusText,
                    onEditProfile = onEditProfile,
                )
            } else if (skin == UiSkin.Nova) {

                Exp2IdentityHero(
                    username = username,
                    displayName = displayName,
                    avatarModel = avatarModel,
                    statusText = statusText,
                    onEditProfile = onEditProfile,
                )
            } else {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.m)
                    .clip(Corners.group)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(displayName, avatarModel, 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                    Text(displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@$username", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    statusText?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(onClick = onEditProfile, shape = Corners.button) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Text("edit", Modifier.padding(start = Spacing.xs))
                }
            }
            }

            Group("preferences")
            GroupCard {
                MenuRow(Icons.Outlined.Palette, "appearance", "theme, accent, wallpaper", onAppearance, cyanBadge = true)
                RowDivider()
                MenuRow(Icons.Outlined.Notifications, "notifications", "background alerts, badges, sound and custom scopes", onNotifications, cyanBadge = true)
                RowDivider()
                MenuRow(Icons.Outlined.Shield, "privacy & safety", "receipts, visibility, blocking", onPrivacy)
            }

            Group("account")
            GroupCard {
                MenuRow(Icons.Outlined.Person, "edit profile", "name, avatar, status", onEditProfile)
                RowDivider()
                MenuRow(Icons.Outlined.Groups, "bird nests", "manage your nests and channels", onNests)
                RowDivider()
                MenuRow(Icons.Outlined.Devices, "devices", "active sessions", onSessions)
                RowDivider()
                MenuRow(Icons.Outlined.History, "login history", "recent sign-ins", onHistory)
                RowDivider()
                MenuRow(Icons.Outlined.Lock, "two-factor auth", "extra account security", onSecurity)
            }

            Spacer(Modifier.height(Spacing.l))
            GroupCard {
                MenuRow(Icons.AutoMirrored.Outlined.Logout, "sign out", null, onSignOut, danger = true)
            }
            Spacer(Modifier.height(Spacing.huge))
        }
    }
}

@Composable
private fun NovaIdentityHero(
    username: String,
    displayName: String,
    avatarModel: Any?,
    statusText: String?,
    onEditProfile: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(bottom = Spacing.m)
            .heroAppear()
            .novaElevation(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, accent, glow = true)
            // soft accent→cyan cover wash bathes the top of the card
            .background(Brush.linearGradient(NovaGradients.heroWash(accent)))
            .padding(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.novaHalo(accent, alpha = NovaDepth.haloAlpha)
                    .size(76.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    // dual-accent presence ring: iris → cyan
                    .border(3.dp, Brush.linearGradient(listOf(accent, NovaColors.Cyan)), CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) { Avatar(displayName, avatarModel, 62.dp) }
            Column(Modifier.weight(1f).padding(start = Spacing.l)) {
                Text(displayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)

                Text("@$username", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                statusText?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.l)
                .clickableScale(onClick = onEditProfile)
                .novaElevation(NovaCorners.button, MaterialTheme.colorScheme.surfaceContainerHigh, accent, glow = true)
                .background(Brush.linearGradient(NovaGradients.cta(accent)))
                .heightIn(min = 52.dp)
                .padding(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Text("edit profile", Modifier.padding(start = Spacing.s), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun Exp2IdentityHero(
    username: String,
    displayName: String,
    avatarModel: Any?,
    statusText: String?,
    onEditProfile: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = Spacing.m)
            .clip(NovaCorners.card)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            )
            .padding(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp),
                contentAlignment = Alignment.Center,
            ) { Avatar(displayName, avatarModel, 58.dp) }
            Column(Modifier.weight(1f).padding(start = Spacing.l)) {
                Text(displayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@$username", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                statusText?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Button(onClick = onEditProfile, shape = NovaCorners.button, modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
            Text("edit profile", Modifier.padding(start = Spacing.s))
        }
    }
}

@Composable
internal fun GroupCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    when (LocalUiSkin.current) {
        UiSkin.Galaxy ->

            Column(
                Modifier.fillMaxWidth().novaElevation(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary),
                content = content,
            )
        UiSkin.Nova ->

            Column(
                Modifier.fillMaxWidth().clip(NovaCorners.card).background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), NovaCorners.card),
                content = content,
            )
        UiSkin.Classic ->
            Column(
                Modifier.fillMaxWidth().clip(Corners.group).background(MaterialTheme.colorScheme.surfaceContainer),
                content = content,
            )
    }
}

@Composable
internal fun RowDivider() {
    val inset = if (LocalExperimentalRedesign.current) Spacing.l + 44.dp + Spacing.m else Spacing.l + Dimens.iconBadge + Spacing.m
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { app.pigeonsms.data.NotificationPrefsStore(context) }
    val scope = rememberCoroutineScope()
    val api = (context.applicationContext as? app.pigeonsms.PigeonApp)?.container?.api
    var global by remember { mutableStateOf(store.get()) }
    var userMode by remember { mutableStateOf(store.get("user", "").mode) }
    var channelMode by remember { mutableStateOf(store.get("channel", "").mode) }
    var spaceMode by remember { mutableStateOf(store.get("space", "").mode) }
    var userId by rememberSaveable { mutableStateOf("") }
    var channelId by rememberSaveable { mutableStateOf("") }
    var spaceId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { api?.notificationPreferences() }.getOrNull()?.preferences?.firstOrNull { it.scope_type == "global" }?.let { remote ->
            val synced = app.pigeonsms.data.NotificationPrefs(remote.mode, remote.sound, remote.vibration, remote.badge)
            store.put("global", "", synced)
            global = synced
        }
    }

    fun save(next: app.pigeonsms.data.NotificationPrefs, type: String = "global", id: String = "") {
        store.put(type, id, next)
        if (type == "global") global = next
        if (type == "global" || id.isNotBlank()) scope.launch { runCatching { api?.setNotificationPreference(type, id, next.mode, next.sound, next.vibration, next.badge) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSubHeader("notifications", onBack)
        Column(Modifier.padding(horizontal = Spacing.l)) {
            Group("general")
            GroupCard {
                ToggleRow("allow notifications", global.mode != "mute") {
                    save(global.copy(mode = if (it) "all" else "mute"))
                }
                ToggleDivider()
                ToggleRow("notification badges", global.badge) {
                    save(global.copy(badge = it))
                }
                ToggleDivider()
                ToggleRow("sound", global.sound) {
                    save(global.copy(sound = it))
                }
                ToggleDivider()
                ToggleRow("vibration", global.vibration) {
                    save(global.copy(vibration = it))
                }
            }
            Group("custom scopes")
            ScopeModeRow(Icons.Outlined.Person, "users", "Only mentions / all / mute", userId, { userId = it }, userMode) { userMode = it; if (userId.isNotBlank()) save(store.get("user", userId).copy(mode = it), "user", userId) }
            ScopeModeRow(Icons.Outlined.Forum, "channels", "Override one channel at a time", channelId, { channelId = it }, channelMode) { channelMode = it; if (channelId.isNotBlank()) save(store.get("channel", channelId).copy(mode = it), "channel", channelId) }
            ScopeModeRow(Icons.Outlined.Groups, "bird nests", "Override one bird nest at a time", spaceId, { spaceId = it }, spaceMode) { spaceMode = it; if (spaceId.isNotBlank()) save(store.get("space", spaceId).copy(mode = it), "space", spaceId) }
            Text(
                "Mentions-only still delivers @user and @everyone alerts. Individual channel, nest and user overrides can be edited from their info menus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.l),
            )
            Spacer(Modifier.height(Spacing.huge))
        }
    }
}

@Composable
private fun ScopeModeRow(icon: ImageVector, title: String, detail: String, scopeId: String, onScopeId: (String) -> Unit, mode: String, onMode: (String) -> Unit) {
    val skin = LocalUiSkin.current
    val galaxy = skin == UiSkin.Galaxy
    val nova = skin == UiSkin.Nova
    val accent = MaterialTheme.colorScheme.primary
    val container = when (skin) {

        UiSkin.Galaxy -> Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)
            .novaElevation(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, accent)

        UiSkin.Nova -> Modifier.fillMaxWidth().padding(vertical = Spacing.xxs).clip(NovaCorners.card).background(MaterialTheme.colorScheme.surfaceContainer)
        UiSkin.Classic -> Modifier.fillMaxWidth().padding(vertical = Spacing.xxs).clip(Corners.group).background(MaterialTheme.colorScheme.surfaceContainer)
    }
    Box(container) {
        Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = Spacing.m))

                Text(modeLabel(mode), style = MaterialTheme.typography.labelMedium, color = if (galaxy) MaterialTheme.colorScheme.secondary else accent)
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.xxl, top = Spacing.xxs))
            OutlinedTextField(value = scopeId, onValueChange = onScopeId, label = { Text("${title.dropLastWhile { it == 's' }} id") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs), shape = if (galaxy || nova) NovaCorners.input else Corners.input)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                listOf("all" to "all", "mentions" to "mentions", "mute" to "mute").forEach { (value, label) ->

                    if (galaxy) {
                        NovaTag(selected = mode == value, onClick = { onMode(value) }) { Text(label) }
                    } else {
                        androidx.compose.material3.FilterChip(selected = mode == value, onClick = { onMode(value) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

private fun modeLabel(mode: String) = when (mode) {
    "mentions" -> "mentions only"
    "mute" -> "muted"
    else -> "all messages"
}

@Composable
internal fun Group(label: String) {
    when (LocalUiSkin.current) {
        UiSkin.Galaxy ->
            if (label.isNotBlank()) NovaSectionLabel(label, modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.m, start = Spacing.m))
            else Spacer(Modifier.height(Spacing.l))
        UiSkin.Nova ->

            if (label.isNotBlank()) Text(label.lowercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.m, start = Spacing.s))
            else Spacer(Modifier.height(Spacing.l))
        UiSkin.Classic ->
            if (label.isNotBlank()) Text(label.lowercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.s, start = Spacing.xs))
            else Spacer(Modifier.height(Spacing.l))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, detail: String?, onClick: () -> Unit, danger: Boolean = false, cyanBadge: Boolean = false) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val skin = LocalUiSkin.current
    val galaxy = skin == UiSkin.Galaxy
    val nova = skin == UiSkin.Nova
    val experimental = galaxy || nova

    val badgeTint = if (galaxy && cyanBadge && !danger) MaterialTheme.colorScheme.secondary else accent
    val badgeSize = if (experimental) 44.dp else Dimens.iconBadge
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = if (experimental) 68.dp else Dimens.settingsRowHeight)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (galaxy) {
            // tactile rounded-square: soft vertical gradient + hairline rim
            Box(
                Modifier.size(badgeSize).clip(NovaCorners.iconBadge)
                    .background(
                        Brush.verticalGradient(
                            listOf(badgeTint.copy(alpha = 0.26f), badgeTint.copy(alpha = 0.12f)),
                        ),
                    )
                    .border(1.dp, badgeTint.copy(alpha = NovaDepth.rimAccent), NovaCorners.iconBadge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = badgeTint, modifier = Modifier.size(22.dp))
            }
        } else {

        Box(
            Modifier.size(badgeSize).clip(if (nova) NovaCorners.iconBadge else Corners.iconBadge)
                .background(accent.copy(alpha = if (nova) 0.18f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(if (nova) 22.dp else Dimens.iconSmall))
        }
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
            Text(label, style = if (experimental) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = if (danger) accent else MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = if (experimental) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (!danger) {
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(if (experimental) 22.dp else 18.dp),
            )
        }
    }
}

@Composable
internal fun SettingsSubHeader(title: String, onBack: () -> Unit) {
    when (LocalUiSkin.current) {
        UiSkin.Galaxy ->
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = Spacing.l, bottom = Spacing.m)) {
                NovaIconBadgeButton(onClick = onBack, size = 44.dp) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", Modifier.size(20.dp))
                }
                Text(
                    title.lowercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.m, start = Spacing.xs).heroAppear(),
                )
            }
        UiSkin.Nova ->

            Column(Modifier.fillMaxWidth().padding(top = Spacing.xxl, bottom = Spacing.m)) {
                IconButton(onBack, Modifier.background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(title.lowercase(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.s, start = Spacing.xs))
            }
        UiSkin.Classic ->
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xxl, bottom = Spacing.s).heightIn(min = Dimens.topBarHeight), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface) }
                Text(title.lowercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
    }
}

@Composable
fun AppearanceScreen(onBack: () -> Unit, onAppIcon: () -> Unit = {}) {
    val vm: SettingsViewModel = pigeonVm { c, _ -> SettingsViewModel(c.authRepository, c.themeStore) }
    val prefs by vm.prefs.collectAsState(initial = ThemePrefs())
    val context = LocalContext.current
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setWallpaper("custom:$uri")
        }
    }
    var showColorEditor by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l)) {
        SettingsSubHeader("appearance", onBack)

        Text("theme", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.s))
        if (prefs.liquidGlass) {
            LiquidSegmented(
                options = ThemeMode.entries.map { it.name.lowercase() },
                selected = ThemeMode.entries.indexOf(prefs.mode),
                onSelect = { vm.setMode(ThemeMode.entries[it]) },
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ThemeMode.entries.forEach { m ->
                    val on = prefs.mode == m
                    Box(
                        Modifier.weight(1f).clip(Corners.button).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer).clickable { vm.setMode(m) }.padding(vertical = Spacing.m),
                        contentAlignment = Alignment.Center,
                    ) { Text(m.name.lowercase(), style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                }
            }
        }

        Spacer(Modifier.height(Spacing.l))
        Text("accent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.s))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            val novaSel = LocalExperimentalRedesign.current
            PigeonAccents.forEach { a ->
                val on = prefs.accent == a.key
                Box(
                    Modifier

                        .then(if (on && novaSel) Modifier.novaHalo(a.bright, alpha = NovaDepth.glowStrong) else Modifier)
                        .size(44.dp).background(a.bright, CircleShape).clickable { vm.setAccent(a.key) }
                        .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                )
            }
            val customOn = prefs.accent.startsWith("custom:")
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                    .clickable { showColorEditor = true }
                    .then(if (customOn) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Tune, "custom color", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        if (showColorEditor) {
            ColorEditorDialog(current = prefs.accent, onPick = { vm.setAccent(it) }, onDismiss = { showColorEditor = false })
        }

        Spacer(Modifier.height(Spacing.l))
        Text("wallpaper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.s))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PigeonWallpapers.forEach { wp ->
                val on = (prefs.wallpaper ?: "none") == wp.key
                val swatch = if (wp.stops.isEmpty()) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier.background(Brush.linearGradient(wp.stops))
                Box(
                    Modifier.size(52.dp).clip(Corners.card).then(swatch)
                        .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, Corners.card) else Modifier)
                        .clickable { vm.setWallpaper(if (wp.key == "none") null else wp.key) },
                    contentAlignment = Alignment.Center,
                ) { if (wp.key == "none") Text("none", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val customOn = prefs.wallpaper?.startsWith("custom:") == true
            Box(
                Modifier.size(52.dp).clip(Corners.card).background(MaterialTheme.colorScheme.surfaceContainer)
                    .then(if (customOn) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, Corners.card) else Modifier)
                    .clickable { wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Image, "custom image", tint = MaterialTheme.colorScheme.primary) }
        }
        if (prefs.wallpaper != null) {
            Text("wallpaper dim", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.m))
            if (prefs.liquidGlass) LiquidSlider(value = prefs.wallpaperDim, onValueChange = vm::setWallpaperDim, valueRange = 0f..0.85f)
            else Slider(value = prefs.wallpaperDim, onValueChange = vm::setWallpaperDim, valueRange = 0f..0.85f)
        }

        Spacer(Modifier.height(Spacing.l))
        GroupCard {
            MenuRow(Icons.Outlined.Image, "app icon", "change the launcher icon", onAppIcon)
        }

        Spacer(Modifier.height(Spacing.l))
        GroupCard {
            ToggleRow("reduced motion", prefs.reducedMotion, vm::setReducedMotion)
            ToggleDivider()
            ToggleRow("liquid glass (experimental)", prefs.liquidGlass, vm::setLiquidGlass)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                ToggleDivider()
                ToggleRow("material you colors", prefs.dynamicColor, vm::setDynamicColor)
            }
        }

        Spacer(Modifier.height(Spacing.l))
        Text("ui skin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.s))
        SkinPicker(current = prefs.uiSkin, onSelect = vm::setUiSkin)
        Spacer(Modifier.height(Spacing.huge))
    }
}

@Composable
private fun SkinPicker(current: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SkinOption(
            key = "classic",
            title = "classic",
            desc = "the original look — untouched",
            selected = current == "classic",
            onSelect = onSelect,
        )
        SkinOption(
            key = "nova",
            title = "nova",
            desc = "cleaner & flatter — calm nav, subtle depth",
            selected = current == "nova",
            onSelect = onSelect,
        )
        SkinOption(
            key = "galaxy",
            title = "galaxy",
            desc = "deep & glowy — aurora, glow, big soft shadows",
            selected = current == "galaxy",
            onSelect = onSelect,
        )
    }
}

@Composable
private fun SkinOption(
    key: String,
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (selected) Modifier.border(2.dp, accent, Corners.card)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), Corners.card),
            )
            .clickable { onSelect(key) }
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit, onBlocked: () -> Unit) {
    val vm: SettingsViewModel = pigeonVm { c, _ -> SettingsViewModel(c.authRepository, c.themeStore) }
    val prefs by vm.prefs.collectAsState(initial = ThemePrefs())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l)) {
        SettingsSubHeader("privacy & safety", onBack)
        GroupCard {
            ToggleRow("read receipts", prefs.readReceipts, vm::setReadReceipts)
            ToggleDivider()
            ToggleRow("invisible mode", prefs.invisible, vm::setInvisible)
        }
        Spacer(Modifier.height(Spacing.l))
        GroupCard {
            MenuRow(Icons.Outlined.Shield, "blocked users", "manage who can't reach you", onBlocked)
        }
        Spacer(Modifier.height(Spacing.huge))
    }
}

@Composable
private fun ToggleDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = Spacing.l),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun ToggleRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val nova = LocalExperimentalRedesign.current
    Row(Modifier.fillMaxWidth().heightIn(min = if (nova) 68.dp else Dimens.settingsRowHeight).padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(label, style = if (nova) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = if (nova) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (LocalLiquidGlass.current) {
            LiquidSwitch(checked = checked, onCheckedChange = onChange)
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowHeight).padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (LocalLiquidGlass.current) {
            LiquidSwitch(checked = checked, onCheckedChange = onChange)
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        }
    }
}

@Composable
private fun ColorEditorDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = remember {
        val hsv = floatArrayOf(280f, 0.68f, 0.95f)
        if (current.startsWith("custom:")) {
            runCatching {
                android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(current.removePrefix("custom:")), hsv)
            }
        }
        hsv
    }
    var h by remember { mutableStateOf(initial[0]) }
    var s by remember { mutableStateOf(initial[1]) }
    var v by remember { mutableStateOf(initial[2]) }
    val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val hex = "#%06X".format(0xFFFFFF and colorInt)
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.glassCard(Corners.card, tint = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("custom accent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(Corners.button).background(Color(colorInt)),
                    contentAlignment = Alignment.Center,
                ) { Text(hex.lowercase(), color = if (v > 0.6f) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge) }
                Text("hue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text("saturation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text("brightness", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("cancel") }
                    Button(onClick = { onPick("custom:$hex"); onDismiss() }, modifier = Modifier.weight(1f)) { Text("use") }
                }
            }
        }
    }
}
