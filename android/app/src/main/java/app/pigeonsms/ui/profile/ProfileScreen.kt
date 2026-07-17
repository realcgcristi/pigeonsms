package app.pigeonsms.ui.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.design.theme.PigeonAccents
import app.pigeonsms.design.theme.PigeonColors
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.MutualSpaceDto
import app.pigeonsms.network.ProfileDto
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.liquidLens
import app.pigeonsms.ui.util.presence
import app.pigeonsms.ui.util.rememberTilt
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_AVATAR_BYTES = 8 * 1024 * 1024
private const val MAX_BANNER_BYTES = 16 * 1024 * 1024

@Composable
fun ProfileScreen(userId: String, onBack: () -> Unit, isSelf: Boolean = false) {
    val vm: ProfileViewModel = pigeonVm(key = "profile-$userId") { c, _ -> ProfileViewModel(c.socialRepository, userId) }
    val ui by vm.ui.collectAsState()

    when {
        ui.loading -> ProfileStatus(onBack, "loading profile...")
        ui.profile == null -> ProfileStatus(onBack, ui.error ?: "couldn't load profile", "try again", vm::load)
        else -> ProfileContent(
            ui.profile!!,
            vm::mediaUrl,
            onBack,
            onBlock = if (isSelf) null else ({ vm.block(onBack) }),
            mutualSpaces = if (isSelf) emptyList() else ui.mutualSpaces,
        )
    }
}

@Composable
private fun ProfileContent(
    profile: ProfileDto,
    mediaUrl: (String?) -> String?,
    onBack: () -> Unit,
    onBlock: (() -> Unit)? = null,
    mutualSpaces: List<MutualSpaceDto> = emptyList(),
) {
    val accent = profileColor(profile.accent, MaterialTheme.colorScheme.primary)
    val bannerColor = profileColor(profile.banner_color, MaterialTheme.colorScheme.surfaceContainerHigh)
    val name = profile.display_name?.takeIf { it.isNotBlank() } ?: profile.username
    val online = presence(profile.last_online)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ProfileHero(
            name = name,
            avatarModel = mediaUrl(profile.avatar_key),
            bannerModel = mediaUrl(profile.banner_key),
            bannerColor = bannerColor,
            accentColor = accent,
            onBack = onBack,
            sharedKey = "avatar-${profile.id}",
        )
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(PigeonMotion.smooth()) + slideInVertically(PigeonMotion.smooth()) { it / 10 },
        ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            // identity block: name, handle, pronouns chip
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                profile.pronouns?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier
                            .padding(start = Spacing.s)
                            .background(accent.copy(alpha = 0.14f), CircleShape)
                            .padding(horizontal = Spacing.m, vertical = Spacing.xxs),
                    )
                }
                if (online) {
                    Box(Modifier.padding(start = Spacing.s).size(8.dp).background(PigeonColors.Mint, CircleShape))
                    Text("online", style = MaterialTheme.typography.labelMedium, color = PigeonColors.Mint, modifier = Modifier.padding(start = Spacing.xs))
                }
            }

            profile.status_text?.takeIf { it.isNotBlank() }?.let {
                Row(
                    Modifier
                        .padding(top = Spacing.l)
                        .fillMaxWidth()
                        .clip(Corners.bubble)
                        .background(accent.copy(alpha = 0.10f))
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = Spacing.s))
                }
            }

            // about card
            profile.about?.takeIf { it.isNotBlank() }?.let {
                Column(
                    Modifier
                        .padding(top = Spacing.m)
                        .fillMaxWidth()
                        .clip(Corners.group)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Spacing.l),
                ) {
                    Text("about me", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.s))
                }
            }

            // details card
            Column(
                Modifier
                    .padding(top = Spacing.m)
                    .fillMaxWidth()
                    .clip(Corners.group)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(Spacing.l),
            ) {
                Text("details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProfileDetailRow(Icons.Outlined.CalendarToday, "joined", profile.created_at.takeIf { it > 0 }?.let(::profileDate) ?: "recently")
                ProfileDetailRow(
                    Icons.Outlined.Schedule,
                    "last seen",
                    if (online) "online now" else profile.last_online?.let(::profileDate) ?: "a while ago",
                    valueColor = if (online) PigeonColors.Mint else null,
                )
                ProfileDetailRow(Icons.Outlined.AlternateEmail, "handle", "@${profile.username}")
            }

            if (mutualSpaces.isNotEmpty()) {
                Column(
                    Modifier
                        .padding(top = Spacing.m)
                        .fillMaxWidth()
                        .clip(Corners.group)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Spacing.l),
                ) {
                    Text(
                        "mutual nests — ${mutualSpaces.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // horizontal chips: icon disc + name + member count, one calm row
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        mutualSpaces.forEach { space ->
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(start = Spacing.xs, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val iconUrl = mediaUrl(space.icon_square_key ?: space.icon_key)
                                if (iconUrl != null) {
                                    AsyncImage(
                                        model = iconUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        Modifier.size(28.dp).background(accent.copy(alpha = 0.16f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            space.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = accent,
                                        )
                                    }
                                }
                                Text(
                                    space.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = Spacing.s),
                                )
                                Text(
                                    "· ${space.member_count}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = Spacing.xs),
                                )
                            }
                        }
                    }
                }
            }

            if (profile.badges.isNotEmpty()) {
                Column(
                    Modifier
                        .padding(top = Spacing.m)
                        .fillMaxWidth()
                        .clip(Corners.group)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Spacing.l),
                ) {
                    Text("badges", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        profile.badges.forEach { badge ->
                            Text(
                                badge,
                                modifier = Modifier
                                    .background(accent.copy(alpha = 0.12f), CircleShape)
                                    .border(1.dp, accent.copy(alpha = 0.30f), CircleShape)
                                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                            )
                        }
                    }
                }
            }
            if (onBlock != null) {
                var confirmBlock by remember { mutableStateOf(false) }
                HorizontalDivider(Modifier.padding(vertical = Spacing.l), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = { confirmBlock = true }) {
                    Text("block @${profile.username}", color = MaterialTheme.colorScheme.error)
                }
                if (confirmBlock) {
                    AlertDialog(
                        onDismissRequest = { confirmBlock = false },
                        title = { Text("block @${profile.username}?") },
                        text = { Text("they'll be removed from your friends and won't be able to message you. you can unblock them from settings.") },
                        confirmButton = {
                            TextButton(onClick = { confirmBlock = false; onBlock() }) {
                                Text("block", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("cancel") } },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
        }
    }
}

@Composable
private fun ProfileDetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(Dimens.iconBadge).clip(Corners.iconBadge).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconSmall))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.m).width(84.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
}

private fun profileDate(epoch: Long): String = runCatching {
    SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(if (epoch < 10_000_000_000L) epoch * 1000 else epoch))
}.getOrDefault("unknown")

@Composable
private fun ProfileStatus(onBack: () -> Unit, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = Dimens.topBarHeight), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back") }
            Text("profile", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.fillMaxSize().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) {
                OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = Spacing.m)) { Text(action) }
            }
        }
    }
}

@Composable
fun EditProfileScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val profile = ui.profile
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var initialized by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var pronouns by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var accent by rememberSaveable { mutableStateOf("#FF9D76") }
    var bannerColor by rememberSaveable { mutableStateOf("#262130") }
    var avatarPreview by remember { mutableStateOf<Uri?>(null) }
    var bannerPreview by remember { mutableStateOf<Uri?>(null) }
    var pendingMedia by remember { mutableStateOf<ProfileMedia?>(null) }
    var removeTarget by remember { mutableStateOf<ProfileMedia?>(null) }

    LaunchedEffect(profile?.id) {
        if (profile != null && !initialized) {
            displayName = profile.display_name.orEmpty()
            about = profile.about.orEmpty()
            pronouns = profile.pronouns.orEmpty()
            status = profile.status_text.orEmpty()
            accent = profile.accent ?: "#FF9D76"
            bannerColor = profile.banner_color ?: "#262130"
            initialized = true
        }
    }
    LaunchedEffect(ui.saving, ui.error) {
        if (!ui.saving && ui.error != null) {
            if (pendingMedia == ProfileMedia.Avatar) avatarPreview = null
            if (pendingMedia == ProfileMedia.Banner) bannerPreview = null
            pendingMedia = null
        }
    }

    fun pick(uri: Uri, media: ProfileMedia) {
        vm.clearFeedback()
        pendingMedia = media
        if (media == ProfileMedia.Avatar) avatarPreview = uri else bannerPreview = uri
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    readImage(context, uri, if (media == ProfileMedia.Avatar) MAX_AVATAR_BYTES else MAX_BANNER_BYTES)
                }
            }.onSuccess { picked ->
                val complete = {
                    if (media == ProfileMedia.Avatar) avatarPreview = null else bannerPreview = null
                    pendingMedia = null
                }
                if (media == ProfileMedia.Avatar) vm.uploadAvatar(picked.bytes, picked.type, complete)
                else vm.uploadBanner(picked.bytes, picked.type, complete)
            }.onFailure { error ->
                if (media == ProfileMedia.Avatar) avatarPreview = null else bannerPreview = null
                pendingMedia = null
                vm.reportError(error.message ?: "couldn't read that image")
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !ui.saving) pick(uri, ProfileMedia.Avatar)
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !ui.saving) pick(uri, ProfileMedia.Banner)
    }
    val mediaBusy = ui.saving || pendingMedia != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = Dimens.topBarHeight).padding(end = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back") }
            Text("edit profile", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                if (mediaBusy) "working..." else "live preview",
                style = MaterialTheme.typography.labelMedium,
                color = if (mediaBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (profile == null && ui.loading) {
            Column(Modifier.fillMaxWidth().height(240.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l).clip(Corners.card).background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                ProfileHero(
                    name = displayName.ifBlank { profile?.username ?: "you" },
                    avatarModel = avatarPreview ?: vm.mediaUrl(profile?.avatar_key),
                    bannerModel = bannerPreview ?: vm.mediaUrl(profile?.banner_key),
                    bannerColor = profileColor(bannerColor, MaterialTheme.colorScheme.surfaceContainerHigh),
                    accentColor = profileColor(accent, MaterialTheme.colorScheme.primary),
                    onAvatarClick = if (mediaBusy) null else ({ avatarPicker.launch("image/*") }),
                    onBannerClick = if (mediaBusy) null else ({ bannerPicker.launch("image/*") }),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
                    Text(
                        displayName.ifBlank { profile?.username ?: "your name" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("@${profile?.username.orEmpty()}${pronouns.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    status.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.s), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                FilledTonalButton(onClick = { avatarPicker.launch("image/*") }, enabled = !mediaBusy, modifier = Modifier.weight(1f), shape = Corners.button) {
                    Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(20.dp))
                    Text("avatar", Modifier.padding(start = Spacing.s))
                }
                FilledTonalButton(onClick = { bannerPicker.launch("image/*") }, enabled = !mediaBusy, modifier = Modifier.weight(1f), shape = Corners.button) {
                    Icon(Icons.Outlined.Image, null, Modifier.size(20.dp))
                    Text("banner", Modifier.padding(start = Spacing.s))
                }
            }
            if (profile?.avatar_key != null || profile?.banner_key != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    if (profile?.avatar_key != null) {
                        TextButton(
                            onClick = { vm.clearFeedback(); removeTarget = ProfileMedia.Avatar },
                            enabled = !mediaBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp))
                            Text("remove avatar", Modifier.padding(start = Spacing.xs))
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (profile?.banner_key != null) {
                        TextButton(
                            onClick = { vm.clearFeedback(); removeTarget = ProfileMedia.Banner },
                            enabled = !mediaBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp))
                            Text("remove banner", Modifier.padding(start = Spacing.xs))
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
            Field("display name", displayName, 48) { displayName = it }
            Field("pronouns", pronouns, 32) { pronouns = it }
            Field("status", status, 80) { status = it }
            Field("about me", about, 500, lines = 4) { about = it }

            ColorPicker("accent color", accent, PigeonAccents.map { colorHex(it.bright) }, circle = true) { accent = it }
            ColorPicker(
                "banner color",
                bannerColor,
                listOf("#262130", "#532C30", "#33265F", "#17324A", "#4A2030", "#164333", "#4A3B17"),
                circle = false,
            ) { bannerColor = it }

            AnimatedVisibility(
                visible = ui.error != null || ui.notice != null,
                enter = fadeIn(PigeonMotion.snappy()),
                exit = fadeOut(PigeonMotion.snappy()),
            ) {
                Text(
                    ui.error ?: ui.notice.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.l),
                )
            }

            Button(
                onClick = {
                    vm.save(
                        mapOf(
                            "display_name" to displayName.trim(),
                            "about" to about.trim(),
                            "pronouns" to pronouns.trim(),
                            "status_text" to status.trim(),
                            "accent" to accent,
                            "banner_color" to bannerColor,
                        ),
                    )
                },
                enabled = initialized && !mediaBusy,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.l),
                shape = Corners.button,
            ) {
                if (ui.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Text(if (ui.uploading != null) "updating image..." else "saving...", Modifier.padding(start = Spacing.s))
                } else {
                    Text(if (ui.saved) "saved" else "save profile")
                }
            }
            Spacer(Modifier.height(Spacing.huge))
        }
    }

    removeTarget?.let { target ->
        val label = if (target == ProfileMedia.Avatar) "avatar" else "banner"
        AlertDialog(
            onDismissRequest = { if (!ui.saving) { removeTarget = null; vm.clearFeedback() } },
            title = { Text("remove $label?") },
            text = {
                Column {
                    Text("Your generated profile fallback will be shown instead.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val complete = {
                            if (target == ProfileMedia.Avatar) avatarPreview = null else bannerPreview = null
                            removeTarget = null
                        }
                        if (target == ProfileMedia.Avatar) vm.resetAvatar(complete) else vm.resetBanner(complete)
                    },
                    enabled = !ui.saving,
                ) {
                    if (ui.saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("removing...", Modifier.padding(start = Spacing.s))
                    } else Text("remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null; vm.clearFeedback() }, enabled = !ui.saving) { Text("cancel") }
            },
        )
    }
}

@Composable
private fun ProfileHero(
    name: String,
    avatarModel: Any?,
    bannerModel: Any?,
    bannerColor: Color,
    accentColor: Color,
    onBack: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    onBannerClick: (() -> Unit)? = null,
    sharedKey: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bannerHeight = (maxWidth / 3f).coerceIn(132.dp, 180.dp)
        Box(Modifier.fillMaxWidth().height(bannerHeight + 56.dp)) {
            Box(
                Modifier.fillMaxWidth().height(bannerHeight).background(bannerColor)
                    .then(if (onBannerClick != null) Modifier.clickable(onClickLabel = "change banner", role = Role.Button, onClick = onBannerClick) else Modifier),
            ) {
                if (bannerModel != null) {
                    AsyncImage(
                        model = bannerModel,
                        contentDescription = "$name banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // depth scrim so the avatar + back button always sit on something readable
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.10f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.28f),
                        ),
                    ),
                )
                Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter).background(accentColor))
                if (onBannerClick != null) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = "change banner",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.m).size(32.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(7.dp),
                    )
                }
            }
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.statusBarsPadding().padding(Spacing.s).background(Color.Black.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = Color.White)
                }
            }
            val glassOn = LocalLiquidGlass.current
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = Spacing.l, bottom = Spacing.s).size(Dimens.avatarHero + 8.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .then(
                        if (glassOn) {
                            Modifier.border(
                                1.5.dp,
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.10f), accentColor.copy(alpha = 0.45f)),
                                ),
                                CircleShape,
                            )
                        } else Modifier,
                    )
                    .then(if (onAvatarClick != null) Modifier.clickable(onClickLabel = "change avatar", role = Role.Button, onClick = onAvatarClick) else Modifier)
                    .padding(4.dp),
            ) {
                if (glassOn) {

                    val tilt = rememberTilt()
                    Avatar(name, avatarModel, Dimens.avatarHero, modifier = Modifier.liquidLens(CircleShape, tilt), sharedKey = sharedKey)
                } else {
                    Avatar(name, avatarModel, Dimens.avatarHero, sharedKey = sharedKey)
                }
                if (onAvatarClick != null) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = "change avatar",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).size(30.dp)
                            .background(accentColor, CircleShape).padding(7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(label: String, selected: String, colors: List<String>, circle: Boolean, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.l))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        colors.distinct().forEach { value ->
            val shape = if (circle) CircleShape else Corners.chip
            val selectedModifier = if (selected.equals(value, ignoreCase = true)) {
                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, shape).padding(3.dp)
            } else Modifier.padding(6.dp)
            Box(
                Modifier.size(48.dp).then(selectedModifier).background(profileColor(value, Color.Gray), shape).clickable { onSelect(value) },
            )
        }
    }
}

private fun colorHex(color: Color): String {
    val red = (color.red * 255).toInt()
    val green = (color.green * 255).toInt()
    val blue = (color.blue * 255).toInt()
    return "#%02X%02X%02X".format(red, green, blue)
}

private fun profileColor(value: String?, fallback: Color): Color {
    val hex = value ?: return fallback
    return runCatching { Color(hex.toColorInt()) }.getOrDefault(fallback)
}

@Composable
private fun Field(label: String, value: String, max: Int, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(max)) },
        label = { Text(label) },
        supportingText = { Text("${value.length}/$max", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else 7,
        shape = Corners.input,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
    )
}

private data class PickedImage(val bytes: ByteArray, val type: String)

private fun readImage(context: Context, uri: Uri, maxBytes: Int): PickedImage {
    val resolver = context.contentResolver
    val type = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.startsWith("image/") && it != "image/svg+xml" }
        ?: throw IllegalArgumentException("please choose an image")
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                val maxMb = maxBytes / (1024 * 1024)
                throw IllegalArgumentException("image must be under ${maxMb}mb")
            }
            output.write(buffer, 0, read)
        }
    } ?: throw IllegalArgumentException("couldn't open that image")
    if (output.size() == 0) throw IllegalArgumentException("that image is empty")
    return PickedImage(output.toByteArray(), type)
}
