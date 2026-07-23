package app.pigeonsms.ui.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import app.pigeonsms.design.components.NovaPanel
import app.pigeonsms.design.components.NovaSectionLabel
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.LocalLiquidGlass
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.PigeonAccents
import app.pigeonsms.design.theme.PigeonColors
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.heroAppear
import app.pigeonsms.design.theme.novaAuroraBackground
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.design.theme.rememberNovaPulse
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
    when (LocalUiSkin.current) {
        UiSkin.Nova -> ProfileContentPorted(profile, mediaUrl, onBack, onBlock, mutualSpaces)
        UiSkin.Galaxy -> ProfileContentNova(profile, mediaUrl, onBack, onBlock, mutualSpaces)
        UiSkin.Classic -> ProfileContentClassic(profile, mediaUrl, onBack, onBlock, mutualSpaces)
    }
}

@Composable
private fun ProfileContentClassic(
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
private fun ProfileContentNova(
    profile: ProfileDto,
    mediaUrl: (String?) -> String?,
    onBack: () -> Unit,
    onBlock: (() -> Unit)? = null,
    mutualSpaces: List<MutualSpaceDto> = emptyList(),
) {
    val accent = profileColor(profile.accent, MaterialTheme.colorScheme.primary)
    val cyan = MaterialTheme.colorScheme.secondary
    val bannerColor = profileColor(profile.banner_color, MaterialTheme.colorScheme.surfaceContainerHigh)
    val name = profile.display_name?.takeIf { it.isNotBlank() } ?: profile.username
    val online = presence(profile.last_online)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    // aurora canvas behind the whole scroll so the space-indigo ground has depth
    Box(Modifier.fillMaxSize().novaAuroraBackground(accent, cyan, animate = true)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val coverHeight = (maxWidth * 0.62f).coerceIn(200.dp, 300.dp)
                val avatarSize = 132.dp
                Box(Modifier.fillMaxWidth().height(coverHeight + avatarSize / 2 + Spacing.s)) {
                    // gradient cover — a dual-accent iris→cyan wash bleeding into the void
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(coverHeight)
                            .clip(RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        accent.copy(alpha = 0.95f),
                                        lerp(accent, cyan, 0.35f).copy(alpha = 0.85f),
                                        bannerColor,
                                        MaterialTheme.colorScheme.surface,
                                    ),
                                ),
                            ),
                    ) {
                        val bannerModel = mediaUrl(profile.banner_key)
                        if (bannerModel != null) {
                            AsyncImage(
                                model = bannerModel,
                                contentDescription = "$name banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // scrim down to the void so the overlapping avatar reads
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0.35f to Color.Transparent,
                                    1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                ),
                            ),
                        )
                    }
                    NovaBackButton(onBack, Modifier.statusBarsPadding().padding(Spacing.s))
                    // big overlapping avatar, centered on the fold, lifted by a soft
                    // accent halo and ringed in an iris→cyan gradient
                    val pulse = rememberNovaPulse()
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .novaHalo(accent, alpha = 0.22f + 0.10f * pulse)
                            .size(avatarSize + 14.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .border(
                                4.dp,
                                Brush.linearGradient(listOf(accent, cyan)),
                                CircleShape,
                            )
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(name, mediaUrl(profile.avatar_key), avatarSize, sharedKey = "avatar-${profile.id}")
                        if (online) {

                            Box(
                                Modifier.align(Alignment.BottomEnd)
                                    .novaHalo(cyan, alpha = 0.30f + 0.35f * pulse)
                                    .size(30.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .padding(4.dp)
                                    .background(cyan, CircleShape),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(PigeonMotion.smooth()) + slideInVertically(PigeonMotion.bouncy()) { it / 8 },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // large display name — louder hero end of the ramp
                    Text(
                        name,
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heroAppear(),
                    )
                    Row(
                        Modifier.padding(top = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("@${profile.username}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        profile.pronouns?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                modifier = Modifier
                                    .padding(start = Spacing.s)
                                    .clip(NovaCorners.chip)
                                    .novaElevation(NovaCorners.chip, MaterialTheme.colorScheme.surfaceContainerHigh, accent, accented = true, elevation = 0.dp)
                                    .padding(horizontal = Spacing.m, vertical = Spacing.xxs),
                            )
                        }
                    }
                    // live presence line in cyan when online — reuse the accent pair
                    if (online) {
                        Row(
                            Modifier.padding(top = Spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            val pulse = rememberNovaPulse()
                            Box(
                                Modifier.novaHalo(cyan, alpha = 0.25f + 0.4f * pulse)
                                    .size(8.dp).background(cyan, CircleShape),
                            )
                            Text(
                                "online now",
                                style = MaterialTheme.typography.labelLarge,
                                color = cyan,
                                modifier = Modifier.padding(start = Spacing.s),
                            )
                        }
                    }

                    // status: elevated accent pill with a lit rim
                    profile.status_text?.takeIf { it.isNotBlank() }?.let {
                        Row(
                            Modifier
                                .padding(top = Spacing.l)
                                .novaElevation(NovaCorners.bubble, MaterialTheme.colorScheme.surfaceContainer, accent, accented = true, glow = true, elevation = NovaDepth.cardElevation)
                                .padding(horizontal = Spacing.l, vertical = Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(9.dp).background(accent, CircleShape))
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = Spacing.s), textAlign = TextAlign.Center)
                        }
                    }

                    Spacer(Modifier.height(Spacing.l))

                    // body feels as designed as the hero.
                    var step = 0

                    // about — expressive elevated card
                    profile.about?.takeIf { it.isNotBlank() }?.let {
                        NovaSection(accent, "about me", delayMillis = 40 * step++) {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.s))
                        }
                        Spacer(Modifier.height(Spacing.m))
                    }

                    // details card
                    NovaSection(accent, "details", delayMillis = 40 * step++) {
                        NovaDetailRow(Icons.Outlined.CalendarToday, "joined", profile.created_at.takeIf { it > 0 }?.let(::profileDate) ?: "recently", accent)
                        NovaDetailRow(
                            Icons.Outlined.Schedule,
                            "last seen",
                            if (online) "online now" else profile.last_online?.let(::profileDate) ?: "a while ago",
                            accent,
                            valueColor = if (online) cyan else null,
                        )
                        NovaDetailRow(Icons.Outlined.AlternateEmail, "handle", "@${profile.username}", accent)
                    }

                    // mutual nests — overlapping avatar stack that fans in + names
                    if (mutualSpaces.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.m))
                        NovaSection(accent, "mutual nests — ${mutualSpaces.size}", delayMillis = 40 * step++) {
                            Row(
                                Modifier.padding(top = Spacing.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // overlapping stack of up to 5 icons, each fanning out
                                Box(Modifier.height(46.dp)) {
                                    mutualSpaces.take(5).forEachIndexed { i, space ->
                                        val iconUrl = mediaUrl(space.icon_square_key ?: space.icon_key)
                                        val reduced = LocalReducedMotion.current
                                        var placed by remember(space.id) { mutableStateOf(false) }
                                        LaunchedEffect(space.id) { placed = true }
                                        val offset by animateFloatAsState(
                                            targetValue = if (placed || reduced) 1f else 0f,
                                            animationSpec = NovaMotion.emphasized(),
                                            label = "nestFan",
                                        )
                                        Box(
                                            Modifier
                                                .padding(start = (i * 30 * offset).dp)
                                                .graphicsLayer { alpha = offset }
                                                .size(46.dp)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                                .padding(2.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (iconUrl != null) {
                                                AsyncImage(iconUrl, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                            } else {
                                                Box(Modifier.fillMaxSize().background(Brush.linearGradient(NovaGradients.coverMesh(accent)), CircleShape), contentAlignment = Alignment.Center) {
                                                    Text(space.name.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = accent)
                                                }
                                            }
                                        }
                                    }
                                    if (mutualSpaces.size > 5) {
                                        Box(
                                            Modifier.padding(start = (5 * 30).dp).size(46.dp)
                                                .background(lerp(accent, cyan, 0.4f).copy(alpha = 0.24f), CircleShape)
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) { Text("+${mutualSpaces.size - 5}", style = MaterialTheme.typography.labelMedium, color = cyan) }
                                    }
                                }
                            }
                            Text(
                                mutualSpaces.joinToString("  ·  ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.m),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // badges — expressive elevated chips
                    if (profile.badges.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.m))
                        NovaSection(accent, "badges", delayMillis = 40 * step++) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.m),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                            ) {
                                profile.badges.forEach { badge ->
                                    Text(
                                        badge,
                                        modifier = Modifier
                                            .novaElevation(NovaCorners.chip, MaterialTheme.colorScheme.surfaceContainerHigh, accent, accented = true, elevation = 0.dp)
                                            .padding(horizontal = Spacing.l, vertical = Spacing.s),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = accent,
                                    )
                                }
                            }
                        }
                    }

                    if (onBlock != null) {
                        var confirmBlock by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(Spacing.l))
                        val source = remember { MutableInteractionSource() }
                        val pressed by source.collectIsPressedAsState()
                        val reduced = LocalReducedMotion.current
                        val scale by animateFloatAsState(
                            if (pressed && !reduced) 0.97f else 1f, NovaMotion.press(), label = "blockPress",
                        )
                        Row(
                            Modifier
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .fillMaxWidth()
                                .clip(NovaCorners.button)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.40f), NovaCorners.button)
                                .clickable(interactionSource = source, indication = null) { confirmBlock = true }
                                .padding(vertical = Spacing.m),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("block @${profile.username}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
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
}

@Composable
private fun ProfileContentPorted(
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val coverHeight = (maxWidth * 0.62f).coerceIn(200.dp, 300.dp)
                val avatarSize = 132.dp
                Box(Modifier.fillMaxWidth().height(coverHeight + avatarSize / 2 + Spacing.s)) {
                    // gradient cover — accent bleeds into banner color into surface
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(coverHeight)
                            .clip(RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        accent.copy(alpha = 0.95f),
                                        bannerColor,
                                        MaterialTheme.colorScheme.surface,
                                    ),
                                ),
                            ),
                    ) {
                        val bannerModel = mediaUrl(profile.banner_key)
                        if (bannerModel != null) {
                            AsyncImage(
                                model = bannerModel,
                                contentDescription = "$name banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0.4f to Color.Transparent,
                                    1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                ),
                            ),
                        )
                    }
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.statusBarsPadding().padding(Spacing.s)
                            .background(Color.Black.copy(alpha = 0.32f), CircleShape),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = Color.White)
                    }
                    // big overlapping avatar, centered on the fold
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .size(avatarSize + 12.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .border(4.dp, accent, CircleShape)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(name, mediaUrl(profile.avatar_key), avatarSize, sharedKey = "avatar-${profile.id}")
                        if (online) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(30.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .padding(4.dp).background(PigeonColors.Mint, CircleShape),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(PigeonMotion.smooth()) + slideInVertically(PigeonMotion.bouncy()) { it / 8 },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // large display name
                    Text(
                        name,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.padding(top = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("@${profile.username}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        profile.pronouns?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                modifier = Modifier
                                    .padding(start = Spacing.s)
                                    .background(accent.copy(alpha = 0.16f), NovaCorners.chip)
                                    .padding(horizontal = Spacing.m, vertical = Spacing.xxs),
                            )
                        }
                    }

                    // status: bold accent pill
                    profile.status_text?.takeIf { it.isNotBlank() }?.let {
                        Row(
                            Modifier
                                .padding(top = Spacing.l)
                                .clip(NovaCorners.bubble)
                                .background(accent.copy(alpha = 0.14f))
                                .border(1.dp, accent.copy(alpha = 0.30f), NovaCorners.bubble)
                                .padding(horizontal = Spacing.l, vertical = Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(9.dp).background(accent, CircleShape))
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = Spacing.s), textAlign = TextAlign.Center)
                        }
                    }

                    Spacer(Modifier.height(Spacing.l))

                    // about — expressive card
                    profile.about?.takeIf { it.isNotBlank() }?.let {
                        PortedNovaCard(accent, "about me") {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.s))
                        }
                        Spacer(Modifier.height(Spacing.m))
                    }

                    // details card
                    PortedNovaCard(accent, "details") {
                        PortedNovaDetailRow(Icons.Outlined.CalendarToday, "joined", profile.created_at.takeIf { it > 0 }?.let(::profileDate) ?: "recently", accent)
                        PortedNovaDetailRow(
                            Icons.Outlined.Schedule,
                            "last seen",
                            if (online) "online now" else profile.last_online?.let(::profileDate) ?: "a while ago",
                            accent,
                            valueColor = if (online) PigeonColors.Mint else null,
                        )
                        PortedNovaDetailRow(Icons.Outlined.AlternateEmail, "handle", "@${profile.username}", accent)
                    }

                    // mutual nests — overlapping avatar stack + names
                    if (mutualSpaces.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.m))
                        PortedNovaCard(accent, "mutual nests — ${mutualSpaces.size}") {
                            Row(
                                Modifier.padding(top = Spacing.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // overlapping stack of up to 5 icons
                                Box(Modifier.height(44.dp)) {
                                    mutualSpaces.take(5).forEachIndexed { i, space ->
                                        val iconUrl = mediaUrl(space.icon_square_key ?: space.icon_key)
                                        Box(
                                            Modifier
                                                .padding(start = (i * 30).dp)
                                                .size(44.dp)
                                                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                                .padding(2.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (iconUrl != null) {
                                                AsyncImage(iconUrl, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                            } else {
                                                Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                                                    Text(space.name.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = accent)
                                                }
                                            }
                                        }
                                    }
                                    if (mutualSpaces.size > 5) {
                                        Box(
                                            Modifier.padding(start = (5 * 30).dp).size(44.dp)
                                                .background(accent.copy(alpha = 0.20f), CircleShape)
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) { Text("+${mutualSpaces.size - 5}", style = MaterialTheme.typography.labelMedium, color = accent) }
                                    }
                                }
                            }
                            Text(
                                mutualSpaces.joinToString("  ·  ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.m),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // badges — expressive chips
                    if (profile.badges.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.m))
                        PortedNovaCard(accent, "badges") {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.m),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                            ) {
                                profile.badges.forEach { badge ->
                                    Text(
                                        badge,
                                        modifier = Modifier
                                            .background(accent.copy(alpha = 0.14f), NovaCorners.chip)
                                            .border(1.dp, accent.copy(alpha = 0.35f), NovaCorners.chip)
                                            .padding(horizontal = Spacing.l, vertical = Spacing.s),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = accent,
                                    )
                                }
                            }
                        }
                    }

                    if (onBlock != null) {
                        var confirmBlock by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(Spacing.l))
                        OutlinedButton(
                            onClick = { confirmBlock = true },
                            shape = NovaCorners.button,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
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
}

@Composable
private fun PortedNovaCard(accent: Color, title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(NovaCorners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), NovaCorners.card)
            .padding(Spacing.l),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
        content()
    }
}

@Composable
private fun PortedNovaDetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, accent: Color, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(NovaCorners.iconBadge).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.m).width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun NovaBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.88f else 1f, NovaMotion.press(), label = "backPress",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.36f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = "back",
                role = Role.Button,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = Color.White)
    }
}

@Composable
private fun NovaSection(
    accent: Color,
    title: String,
    delayMillis: Int = 0,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    NovaPanel(
        modifier = Modifier.fillMaxWidth().heroAppear(delayMillis = delayMillis),
        shape = NovaCorners.card,
    ) {
        NovaSectionLabel(title, accent = false)
        content()
    }
}

@Composable
private fun NovaDetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, accent: Color, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(NovaCorners.iconBadge)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.10f))))
                .border(1.dp, Color.White.copy(alpha = NovaDepth.rimTop), NovaCorners.iconBadge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.m).width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
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
    if (LocalExperimentalRedesign.current) {
        ProfileStatusNova(onBack, message, action, onAction)
        return
    }
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
private fun ProfileStatusNova(onBack: () -> Unit, message: String, action: String?, onAction: (() -> Unit)?) {
    val accent = MaterialTheme.colorScheme.primary
    val cyan = MaterialTheme.colorScheme.secondary
    Box(Modifier.fillMaxSize().novaAuroraBackground(accent, cyan, animate = true)) {
        NovaBackButton(onBack, Modifier.statusBarsPadding().padding(Spacing.s))
        if (action != null && onAction != null) {
            // error state — accent-washed disc + retry
            Column(
                Modifier.fillMaxSize().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val pulse = rememberNovaPulse()
                Box(
                    Modifier
                        .novaHalo(accent, alpha = 0.14f + 0.10f * pulse)
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.10f))))
                        .border(1.dp, Color.White.copy(alpha = NovaDepth.rimTop), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = accent, modifier = Modifier.size(34.dp))
                }
                Text(
                    message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.l),
                )
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val reduced = LocalReducedMotion.current
                val scale by animateFloatAsState(
                    if (pressed && !reduced) 0.96f else 1f, NovaMotion.press(), label = "retryPress",
                )
                Row(
                    Modifier
                        .padding(top = Spacing.l)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(NovaCorners.button)
                        .background(Brush.linearGradient(NovaGradients.cta(accent)))
                        .clickable(interactionSource = source, indication = null, onClick = onAction)
                        .padding(horizontal = Spacing.xl, vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        } else {
            // first-load skeleton — mirrors the real hero shape
            ProfileSkeleton()
        }
    }
}

@Composable
private fun ProfileSkeleton() {
    val reduced = LocalReducedMotion.current
    val shimmer = if (reduced) 0.5f else {
        val t = rememberInfiniteTransition(label = "profileSkeleton")
        val v by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "shimmerV",
        )
        v
    }
    val base = MaterialTheme.colorScheme.surfaceContainer
    val hi = MaterialTheme.colorScheme.surfaceContainerHigh
    val block = lerp(base, hi, shimmer)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverHeight = (maxWidth * 0.62f).coerceIn(200.dp, 300.dp)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(coverHeight + 66.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(coverHeight)
                        .clip(RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))
                        .background(block),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).size(146.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(6.dp)
                        .background(hi, CircleShape),
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = Spacing.s).width(200.dp).height(34.dp).clip(NovaCorners.chip).background(block))
                Box(Modifier.padding(top = Spacing.m).width(120.dp).height(18.dp).clip(NovaCorners.chip).background(block))
                Spacer(Modifier.height(Spacing.l))
                repeat(2) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = Spacing.m).height(120.dp)
                            .clip(NovaCorners.card).background(block),
                    )
                }
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
