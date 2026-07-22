package app.pigeonsms.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import app.pigeonsms.ui.util.LocalNavAnimatedScope
import app.pigeonsms.ui.util.LocalSharedTransitionScope
import app.pigeonsms.ui.util.liquidGlassThumb
import app.pigeonsms.ui.util.glassPanel
import app.pigeonsms.design.theme.LocalLiquidGlass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pigeonsms.data.ChatAppearanceStore
import app.pigeonsms.data.LocalSession
import kotlinx.coroutines.flow.first
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.chat.ChatScreen
import app.pigeonsms.ui.friends.FriendsScreen
import app.pigeonsms.ui.profile.EditProfileScreen
import app.pigeonsms.ui.profile.ProfileScreen
import app.pigeonsms.ui.profile.ProfileViewModel
import app.pigeonsms.ui.settings.AppIconScreen
import app.pigeonsms.ui.settings.AppearanceScreen
import app.pigeonsms.ui.settings.BlockedScreen
import app.pigeonsms.ui.settings.DevicesScreen
import app.pigeonsms.ui.settings.PrivacyScreen
import app.pigeonsms.ui.settings.HistoryScreen
import app.pigeonsms.ui.settings.SecurityScreen
import app.pigeonsms.ui.settings.SettingsScreen
import app.pigeonsms.ui.settings.NotificationSettingsScreen
import app.pigeonsms.ui.forum.ForumScreen
import app.pigeonsms.ui.spaces.SpacesScreen
import app.pigeonsms.ui.util.Avatar

private data class Tab(val route: String, val label: String, val icon: ImageVector, val iconRes: Int? = null)
private val tabs = listOf(
    Tab("messages", "messages", Icons.Rounded.Forum),
    Tab("friends", "friends", Icons.Rounded.Mood),
    Tab("spaces", "bird nests", Icons.Rounded.Groups, iconRes = app.pigeonsms.R.drawable.ic_nest),
    Tab("you", "you", Icons.Rounded.Person),
)

private fun enc(s: String) = Uri.encode(s)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppShell(session: LocalSession) {
    val nav = rememberNavController()
    val app: AppViewModel = pigeonVm {
        c, _ -> AppViewModel(
            c.gateway,
            c.socialRepository,
            c.chatRepository,
            c.authRepository,
            session.userId,
            session.token,
        )
    }
    LaunchedEffect(session.userId, session.token) {
        app.activateSession(session.userId, session.token)
    }

    // google-services.json) — the app works fine without push.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(session.userId) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            if (com.google.firebase.FirebaseApp.getApps(appContext).isNotEmpty()) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token -> app.registerPushToken(token) }
                    .addOnFailureListener {

                        // persisted on rotation so it still reaches the backend.
                        appContext.getSharedPreferences("push_token", android.content.Context.MODE_PRIVATE)
                            .getString("fcm_token", null)
                            ?.let { app.registerPushToken(it) }
                    }
            }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "messages"
    val topLevel = route in tabs.map { it.route }
    val selfProfile: ProfileViewModel = pigeonVm(key = "self-profile-${session.userId}") { c, _ ->
        ProfileViewModel(c.socialRepository, session.userId)
    }
    val selfProfileUi by selfProfile.ui.collectAsState()
    val profile = selfProfileUi.profile
    val profileName = profile?.display_name?.takeIf { it.isNotBlank() } ?: session.username
    val profileAvatar = selfProfile.mediaUrl(profile?.avatar_key)
    val home by app.home.collectAsState()
    val messageBadge = home.dms.sumOf { it.unread }.coerceAtLeast(0)
    val nestBadge = home.spaces.sumOf { space -> space.channels.sumOf { it.unread } }.coerceAtLeast(0)

    LaunchedEffect(route, session.userId, selfProfileUi.saving) {
        if (route == "you" && !selfProfileUi.saving) selfProfile.load()
    }

    val hazeState = remember { HazeState() }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (topLevel) BottomBar(route, profileName, profileAvatar, hazeState, messageBadge, nestBadge) { tab ->
                nav.navigate(tab.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { pad ->
        Box {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
        NavHost(
            nav,
            startDestination = "messages",
            modifier = Modifier.hazeSource(hazeState).padding(if (topLevel) pad else androidx.compose.foundation.layout.PaddingValues(0.dp)),
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f) },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(140)) + scaleOut(tween(220), targetScale = 0.97f) },
        ) {
            composable("messages") {
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    MessagesScreen(
                        app = app,
                        onOpenChat = { dm ->
                            nav.navigate("chat/${dm.channel_id}/${enc(dm.peer.display_name ?: dm.peer.username)}?avatar=${enc(dm.peer.avatar_key ?: "")}")
                        },
                        onOpenProfile = { id -> nav.navigate("profile/$id") },
                    )
                }
            }
            composable("friends") {
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    FriendsScreen(app,
                        onOpenChat = { ch, name -> nav.navigate("chat/$ch/${enc(name)}") },
                        onOpenProfile = { id -> nav.navigate("profile/$id") })
                }
            }
            composable("spaces") {
                SpacesScreen(app) { ch, name, kind ->
                    if (kind == "forum") {
                        nav.navigate("forum/$ch/${enc(name)}")
                    } else {
                        nav.navigate("chat/$ch/${enc(name)}?space=true")
                    }
                }
            }
            composable("forum/{cid}/{title}") { entry ->
                val cid = entry.arguments?.getString("cid") ?: return@composable
                val title = entry.arguments?.getString("title") ?: "forum"
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    ForumScreen(
                        channelId = cid,
                        title = title,
                        onBack = { nav.popBackStack() },
                        onOpenProfile = { id -> nav.navigate("profile/$id") },
                    )
                }
            }
            composable("you") {
                SettingsScreen(
                    username = session.username,
                    displayName = profileName,
                    avatarModel = profileAvatar,
                    statusText = profile?.status_text,
                    onSessions = { nav.navigate("devices") },
                    onHistory = { nav.navigate("history") },
                    onSecurity = { nav.navigate("security") },
                    onEditProfile = { nav.navigate("editprofile") },
                    onBlocked = { nav.navigate("blocked") },
                    onAppearance = { nav.navigate("appearance") },
                    onPrivacy = { nav.navigate("privacy") },
                    onNotifications = { nav.navigate("notifications") },
                    onSignOut = { app.viewModelScopeSignOut() },
                )
            }
            composable(
                "chat/{cid}/{title}?avatar={avatar}&space={space}",
                arguments = listOf(
                    androidx.navigation.navArgument("avatar") { defaultValue = "" },
                    androidx.navigation.navArgument("space") { defaultValue = "false" },
                ),
            ) { entry ->
                val cid = entry.arguments?.getString("cid") ?: return@composable
                val title = entry.arguments?.getString("title") ?: "chat"
                val avatar = entry.arguments?.getString("avatar")?.takeIf { it.isNotBlank() }
                val isSpace = entry.arguments?.getString("space")?.toBoolean() == true
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                ChatScreen(
                    channelId = cid,
                    title = title,
                    avatarKey = avatar,
                    selfId = session.userId,
                    selfName = session.username,
                    isAdmin = session.isAdmin,
                    isSpace = isSpace,
                    sessionToken = session.token,
                    typingEvents = app.typingEvents,
                    onBack = { nav.popBackStack() },
                    onActive = { app.activeChannel = it },
                    onOpenProfile = { id -> nav.navigate("profile/$id") },
                )
                }
            }
            composable("profile/{id}") { entry ->
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    val profileId = entry.arguments?.getString("id") ?: session.userId
                    ProfileScreen(profileId, onBack = { nav.popBackStack() }, isSelf = profileId == session.userId)
                }
            }
            composable("editprofile") { EditProfileScreen(selfProfile, onBack = { nav.popBackStack() }) }
            composable("devices") { DevicesScreen(onBack = { nav.popBackStack() }) }
            composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable("security") { SecurityScreen(onBack = { nav.popBackStack() }) }
            composable("blocked") { BlockedScreen(onBack = { nav.popBackStack() }) }
            composable("appearance") { AppearanceScreen(onBack = { nav.popBackStack() }, onAppIcon = { nav.navigate("appicon") }) }
            composable("appicon") {
                AppIconScreen(onBack = { nav.popBackStack() }, onOpenUser = { id -> nav.navigate("profile/$id") })
            }
            composable("privacy") { PrivacyScreen(onBack = { nav.popBackStack() }, onBlocked = { nav.navigate("blocked") }) }
            composable("notifications") { NotificationSettingsScreen(onBack = { nav.popBackStack() }) }
        }
        }
        }

        var ping by remember { androidx.compose.runtime.mutableStateOf<AppViewModel.IncomingPing?>(null) }
        val pingMuteStore = remember { ChatAppearanceStore(appContext.applicationContext) }
        LaunchedEffect(app) {
            app.pings.collect { incoming ->
                // muted chats stay silent — same per-chat toggle the appearance sheet sets
                val muted = runCatching { pingMuteStore.appearance(incoming.channelId).first().muted }.getOrDefault(false)
                if (!muted) ping = incoming
            }
        }
        LaunchedEffect(ping) {
            if (ping != null) {
                kotlinx.coroutines.delay(4_000)
                ping = null
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = ping != null,
            enter = fadeIn(PigeonMotion.snappy()) +
                androidx.compose.animation.slideInVertically(PigeonMotion.snappy()) { -it },
            exit = fadeOut(tween(140)) + androidx.compose.animation.slideOutVertically(tween(180)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val current = ping
            if (current != null) {
                val glass = LocalLiquidGlass.current
                val density = androidx.compose.ui.platform.LocalDensity.current

                // banner dismisses, otherwise it snaps back to center.
                var dragX by remember(current) { androidx.compose.runtime.mutableFloatStateOf(0f) }
                val settledX by androidx.compose.animation.core.animateFloatAsState(
                    dragX, PigeonMotion.snappy(), label = "pingDragX",
                )
                val dismissPx = with(density) { 96.dp.toPx() }
                Row(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.l, vertical = Spacing.s)
                        .fillMaxWidth()
                        .offset { androidx.compose.ui.unit.IntOffset(settledX.roundToInt(), 0) }
                        .graphicsLayer { alpha = (1f - kotlin.math.abs(settledX) / (dismissPx * 2f)).coerceIn(0.25f, 1f) }
                        .pointerInput(current) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (kotlin.math.abs(dragX) > dismissPx) ping = null else dragX = 0f },
                                onDragCancel = { dragX = 0f },
                            ) { change, delta ->
                                change.consume()
                                dragX += delta
                            }
                        }
                        .shadow(12.dp, CircleShape, clip = false)
                        .then(
                            if (glass) {
                                Modifier.glassPanel(CircleShape, MaterialTheme.colorScheme.surfaceContainerHigh)
                            } else {
                                Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), CircleShape)
                            },
                        )
                        .clickable {
                            ping = null

                            val location = home.spaces.asSequence()
                                .flatMap { space -> space.channels.asSequence().map { space to it } }
                                .firstOrNull { (_, channel) -> channel.id == current.channelId }
                            val channel = location?.second
                            if (channel?.kind == "forum") {
                                nav.navigate("forum/${current.channelId}/${enc(channel.name ?: current.title)}")
                            } else {
                                val suffix = if (channel != null) "?space=true" else ""
                                nav.navigate("chat/${current.channelId}/${enc(current.title)}$suffix")
                            }
                        }
                        .padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(vertical = Spacing.xs)) {
                        Text(
                            current.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Text(
                            current.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = { ping = null }) {
                        Icon(
                            Icons.Rounded.Close,
                            "dismiss notification",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun BottomBar(current: String, profileName: String, profileAvatar: Any?, hazeState: HazeState, messageBadge: Int, nestBadge: Int, onSelect: (Tab) -> Unit) {

    // gesture-nav strip reads as part of the bar instead of showing a sliver
    // of screen/wallpaper behind it.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                    ),
                ),
        )
        Box(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        val glass = LocalLiquidGlass.current
        val hazeBg = MaterialTheme.colorScheme.surface
        val hazeTint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (glass) 0.4f else 0.6f)
        val edge = if (glass) {
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.5f), Color.Transparent, Color.White.copy(alpha = 0.12f)),
            )
        } else {
            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
        }
        Row(
            Modifier
                .shadow(16.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .hazeEffect(hazeState) {
                    backgroundColor = hazeBg
                    tints = listOf(HazeTint(hazeTint))
                    blurRadius = if (glass) 32.dp else 24.dp
                }
                .border(if (glass) 1.5.dp else 1.dp, edge, CircleShape)
                .padding(Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavPill(tab, tab.route == current, profileName, profileAvatar, if (tab.route == "messages") messageBadge else if (tab.route == "spaces") nestBadge else 0) { onSelect(tab) }
            }
        }
        }
    }
}

@Composable
private fun NavPill(tab: Tab, selected: Boolean, profileName: String, profileAvatar: Any?, badgeCount: Int, onSelect: () -> Unit) {
    val glass = LocalLiquidGlass.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        PigeonMotion.snappy(), label = "navPillBg",
    )
    val tint by animateColorAsState(
        if (selected) {
            if (glass) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        PigeonMotion.snappy(), label = "navPillTint",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .then(if (glass && selected) Modifier.liquidGlassThumb() else Modifier.background(bg))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Tab,
                onClick = {
                    if (!selected) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onSelect()
                },
            )
            .padding(horizontal = if (selected) Spacing.l else Spacing.m, vertical = Spacing.s)
            .animateContentSize(PigeonMotion.snappy()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (tab.route == "you") {
            Avatar(
                name = profileName,
                model = profileAvatar,
                size = 24.dp,
                modifier = Modifier.border(if (selected) 2.dp else 1.dp, tint, CircleShape),
            )
        } else {
            Box {
                if (tab.iconRes != null) {
                    Icon(painterResource(tab.iconRes), tab.label, tint = tint, modifier = Modifier.size(24.dp))
                } else {
                    Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(24.dp))
                }
                if (badgeCount > 0) {
                    Badge(modifier = Modifier.align(Alignment.TopEnd)) { Text(if (badgeCount > 99) "99+" else badgeCount.toString()) }
                }
            }
        }
        if (selected) {
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}
