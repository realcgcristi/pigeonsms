package app.pigeonsms.ui.friends

import androidx.compose.ui.draw.shadow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import app.pigeonsms.design.components.NovaAnimatedCount
import app.pigeonsms.design.components.NovaHero
import app.pigeonsms.design.components.NovaIconBadgeButton
import app.pigeonsms.design.components.NovaPillButton
import app.pigeonsms.design.components.NovaSectionLabel
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.PigeonColors
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.design.theme.novaSurface
import app.pigeonsms.design.theme.rememberNovaPulse
import app.pigeonsms.network.FriendDto
import app.pigeonsms.ui.AppViewModel
import app.pigeonsms.ui.ListSectionHeader
import app.pigeonsms.ui.UnreadPill
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.ScreenHeader
import app.pigeonsms.ui.util.SkeletonList
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.presence

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(app: AppViewModel, onOpenChat: (String, String) -> Unit, onOpenProfile: (String) -> Unit) {
    val vm: FriendsViewModel = pigeonVm { c, _ -> FriendsViewModel(c.socialRepository) }
    val home by app.home.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val msg by vm.message.collectAsState()
    val adding by vm.adding.collectAsState()
    LaunchedEffect(Unit) { app.refreshFriends() }

    val nothingYet = home.friends.isEmpty() && home.incoming.isEmpty() && home.outgoing.isEmpty()
    // 3-way skin dispatch. `nova` here means "not the classic skin" — it drives the
    // shared non-classic chrome (sheet corners, empty CTA, tab flavor). The distinct
    // Nova vs Galaxy layouts are branched on `skin` explicitly below.
    val skin = LocalUiSkin.current
    val galaxy = skin == UiSkin.Galaxy
    val nova = skin != UiSkin.Classic

    Column(Modifier.fillMaxSize()) {
        if (galaxy) {
            val friendCount = home.friends.size
            val onlineCount = home.friends.count { presence(it.last_online) }
            val requestCount = home.incoming.size
            val stat = buildString {
                append("$friendCount friend").append(if (friendCount == 1) "" else "s")
                if (onlineCount > 0) append(" · $onlineCount online")
                if (requestCount > 0) append(" · $requestCount request").append(if (requestCount == 1) "" else "s")
            }
            NovaHero(
                title = "friends",
                subtitle = stat,
                // cyan-accented when someone is waiting on you, cyan also flags "online"
                accentSubtitle = requestCount > 0 || onlineCount > 0,
                modifier = Modifier.novaHalo(MaterialTheme.colorScheme.primary, alpha = NovaDepth.haloAlpha * 0.6f),
                action = {
                    NovaIconBadgeButton(onClick = { addOpen = true }) {
                        Icon(Icons.Outlined.PersonAdd, "add friend", Modifier.size(22.dp))
                    }
                },
            )
        } else if (nova) {
            ExpFriendsHeader(
                friendCount = home.friends.size,
                onlineCount = home.friends.count { presence(it.last_online) },
                requestCount = home.incoming.size,
                onAdd = { addOpen = true },
            )
        } else {
            ScreenHeader("friends") {
                IconButton(onClick = { addOpen = true }) { Icon(Icons.Outlined.PersonAdd, "add", tint = MaterialTheme.colorScheme.primary) }
            }
        }
        if (home.friendsLoading && nothingYet) {
            SkeletonList()
        } else if (home.friendsError != null && nothingYet) {
            ErrorState(home.friendsError!!, app::refreshFriends)
        } else if (nothingYet) {
            if (galaxy) {
                Empty("no friends yet", "add someone by their username", Icons.Outlined.PersonAdd) {
                    NovaPillButton(
                        text = "add someone",
                        onClick = { addOpen = true },
                        modifier = Modifier.padding(horizontal = Spacing.xl),
                        leading = { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp)) },
                    )
                }
            } else {
                Empty("no friends yet", "add someone by their username", Icons.Outlined.PersonAdd)
            }
        } else {
            if (galaxy) {
                NovaSegmentedTabs(
                    friendsLabel = "friends",
                    friendsCount = home.friends.size,
                    requestsLabel = "requests",
                    requestsCount = home.incoming.size,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            } else if (nova) {
                ExpSegmentedTabs(
                    friendsLabel = "friends",
                    friendsCount = home.friends.size,
                    requestsLabel = "requests",
                    requestsCount = home.incoming.size,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            } else {
                PillTabs(
                    options = listOf("friends", "requests"),
                    selected = tab,
                    onSelect = { tab = it },
                    badgeCount = home.incoming.size,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = home.friendsLoading,
                onRefresh = { app.refreshFriends() },
                modifier = Modifier.fillMaxSize(),
            ) {
                val fade: FiniteAnimationSpec<Float> = PigeonMotion.snappy()
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(fade).togetherWith(fadeOut(fade)) },
                    label = "friendsTabs",
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    if (galaxy) {
                        if (page == 0) NovaFriendsTab(app, vm, onOpenChat, onOpenProfile)
                        else NovaRequestsTab(app, vm, onOpenProfile)
                    } else if (nova) {
                        if (page == 0) ExpFriendsTab(app, vm, onOpenChat, onOpenProfile)
                        else ExpRequestsTab(app, vm, onOpenProfile)
                    } else {
                        if (page == 0) FriendsTab(app, vm, onOpenChat, onOpenProfile)
                        else RequestsTab(app, vm, onOpenProfile)
                    }
                }
            }
        }
    }

    if (addOpen) {
        var query by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { if (!adding) { addOpen = false; vm.clearMessage() } },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = if (nova) NovaCorners.sheet else androidx.compose.material3.BottomSheetDefaults.ExpandedShape,
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.l).padding(bottom = Spacing.xl)) {
                Text("add a friend", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("they'll get a request to accept", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                Spacer(Modifier.height(Spacing.l))
                OutlinedTextField(query, { query = it.take(32); vm.clearMessage() }, label = { Text("username") }, enabled = !adding, singleLine = true, shape = if (nova) NovaCorners.input else Corners.input, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send))
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s)) }
                Spacer(Modifier.height(Spacing.l))
                if (nova) {
                    NovaPillButton(
                        text = if (adding) "sending..." else "send request",
                        onClick = { if (!adding) vm.add(query) { app.refreshFriends(); addOpen = false; vm.clearMessage() } },
                        modifier = Modifier.fillMaxWidth(),
                        armed = !adding && query.isNotBlank(),
                        leading = if (adding) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) }) else null,
                    )
                } else {
                    Button(onClick = { vm.add(query) { app.refreshFriends(); addOpen = false; vm.clearMessage() } }, enabled = !adding, modifier = Modifier.fillMaxWidth(), shape = Corners.button) {
                        if (adding) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("sending...", Modifier.padding(start = Spacing.s))
                        } else Text("send request")
                    }
                }
            }
        }
    }
}

// --- Classic ---------------------------------------------------------------------

@Composable
private fun FriendsTab(
    app: AppViewModel,
    vm: FriendsViewModel,
    onOpenChat: (String, String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    if (home.friends.isEmpty()) {
        Empty("no friends yet", "accept a request or add someone", Icons.Outlined.PersonAdd)
        return
    }
    val online = home.friends.count { presence(it.last_online) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
        item { ListSectionHeader("${home.friends.size} friends${if (online > 0) " · $online online" else ""}") }
        itemsIndexed(home.friends, key = { _, f -> f.id }) { index, f ->
            FriendRow(f, app.mediaUrl(f.avatar_key), Modifier.itemAppear(index), onClick = { onOpenProfile(f.id) }) {
                FilledTonalIconButton(
                    onClick = { vm.openDm(f.id) { ch -> onOpenChat(ch, f.display_name ?: f.username) } },
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, "message", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun RequestsTab(
    app: AppViewModel,
    vm: FriendsViewModel,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    if (home.incoming.isEmpty() && home.outgoing.isEmpty()) {
        Empty("no pending requests", "requests you send or receive show up here", Icons.Outlined.HourglassEmpty)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
        if (home.incoming.isNotEmpty()) {
            item { ListSectionHeader("incoming") }
            itemsIndexed(home.incoming, key = { _, f -> "in-${f.id}" }) { index, f ->
                FriendRow(f, app.mediaUrl(f.avatar_key), Modifier.itemAppear(index), onClick = { onOpenProfile(f.id) }) {
                    FilledIconButton(
                        onClick = { vm.accept(f.id) { app.refreshFriends() } },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Icon(Icons.Outlined.Check, "accept", Modifier.size(20.dp)) }
                    Spacer(Modifier.width(Spacing.xs))
                    FilledTonalIconButton(onClick = { vm.remove(f.id) { app.refreshFriends() } }) {
                        Icon(Icons.Outlined.Close, "decline", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (home.outgoing.isNotEmpty()) {
            item { ListSectionHeader("sent") }
            itemsIndexed(home.outgoing, key = { _, f -> "out-${f.id}" }) { index, f ->
                FriendRow(f, app.mediaUrl(f.avatar_key), Modifier.itemAppear(index), onClick = { onOpenProfile(f.id) }) {
                    Text("pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Calm segmented tabs: plain track, sliding pill indicator on a smooth spring —
 *  no glass on structural chrome. [badgeCount] marks the second tab. */
@Composable
private fun PillTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badgeCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val count = options.size.coerceAtLeast(1)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        val segW = maxWidth / count
        val spec: FiniteAnimationSpec<Dp> = PigeonMotion.smooth()
        val x by animateDpAsState(segW * selected, spec, label = "tabX")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .padding(Spacing.xs)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (i == 1 && badgeCount > 0) {
                        Spacer(Modifier.width(Spacing.xs))
                        UnreadPill(badgeCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    f: FriendDto,
    avatar: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickableScale(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.then(if (presence(f.last_online)) Modifier.border(2.dp, PigeonColors.Mint, CircleShape).padding(3.dp) else Modifier)) {
            Avatar(f.display_name ?: f.username, avatar, 48.dp, sharedKey = "avatar-${f.id}")
        }
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                (f.display_name ?: f.username).lowercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                f.status_text?.lowercase() ?: "@${f.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.s))
        trailing()
    }
}

// --- NOVA ------------------------------------------------------------------------
// Distinct structure: a hero header with an inline stat line + add action, a
// count-bearing segmented control, and friend/request CARDS (big 56dp avatar,
// bold name, action row) instead of thin rows. Requests get a prominent
// accept/decline action pair on a tinted card.

/** Bold segmented control with per-segment counts and a bouncy sliding indicator. */
@Composable
private fun NovaSegmentedTabs(
    friendsLabel: String,
    friendsCount: Int,
    requestsLabel: String,
    requestsCount: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(friendsLabel to friendsCount, requestsLabel to requestsCount)
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier
            // depth: lifted-top gradient + lit hairline rim so the track floats
            .fillMaxWidth()
            .height(54.dp)
            .novaSurface(NovaCorners.group, MaterialTheme.colorScheme.surfaceContainer, accent)
            .padding(Spacing.xs),
    ) {
        val segW = maxWidth / 2
        val spec: FiniteAnimationSpec<Dp> = PigeonMotion.bouncy()
        val x by animateDpAsState(segW * selected, spec, label = "novaTabX")
        // elastic squash-and-stretch: while the pill is mid-travel (its animated
        // position lags the target), it stretches along the travel axis then
        // settles — matching the glass segmented control's delight.
        val target = segW * selected
        val stretch = 1f + (kotlin.math.abs((x - target).value) / segW.value.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.16f
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = stretch
                    // counter-squash on the cross axis for a springy feel
                    scaleY = 1f - (stretch - 1f) * 0.5f
                }
                // gradient iris→cyan indicator with a soft accent glow underneath
                .androidx_tabGlow(accent)
                .clip(NovaCorners.chip)
                .background(Brush.horizontalGradient(NovaGradients.cta(accent))),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, (label, count) ->
                val active = i == selected
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(NovaCorners.chip)
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (count > 0) {
                        Spacer(Modifier.width(Spacing.xs))
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                                .padding(horizontal = Spacing.s, vertical = 1.dp),
                        ) {
                            NovaAnimatedCount(
                                count = count,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Soft accent glow shadow under the active-tab indicator so the selection reads
 *  as a lit halo rather than a paint chip. */
private fun Modifier.androidx_tabGlow(accent: androidx.compose.ui.graphics.Color): Modifier =
    this.shadow(
        elevation = NovaDepth.raisedElevation,
        shape = NovaCorners.chip,
        clip = false,
        spotColor = accent.copy(alpha = NovaDepth.glowStrong),
        ambientColor = androidx.compose.ui.graphics.Color.Black,
    )

@Composable
private fun NovaFriendsTab(
    app: AppViewModel,
    vm: FriendsViewModel,
    onOpenChat: (String, String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    if (home.friends.isEmpty()) {
        Empty("no friends yet", "accept a request or add someone", Icons.Outlined.PersonAdd)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        itemsIndexed(home.friends, key = { _, f -> f.id }) { index, f ->
            NovaFriendCard(
                f = f,
                avatar = app.mediaUrl(f.avatar_key),
                modifier = Modifier.itemAppear(index),
                onClick = { onOpenProfile(f.id) },
            ) {
                FilledTonalButton(
                    onClick = { vm.openDm(f.id) { ch -> onOpenChat(ch, f.display_name ?: f.username) } },
                    shape = NovaCorners.button,
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("message", Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun NovaRequestsTab(
    app: AppViewModel,
    vm: FriendsViewModel,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    if (home.incoming.isEmpty() && home.outgoing.isEmpty()) {
        Empty("no pending requests", "requests you send or receive show up here", Icons.Outlined.HourglassEmpty)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (home.incoming.isNotEmpty()) {
            item {
                NovaSectionLabel(
                    "incoming · ${home.incoming.size}",
                    accent = true,
                    modifier = Modifier.padding(start = Spacing.m, top = Spacing.m),
                )
            }
            itemsIndexed(home.incoming, key = { _, f -> "in-${f.id}" }) { index, f ->
                NovaFriendCard(
                    f = f,
                    avatar = app.mediaUrl(f.avatar_key),
                    modifier = Modifier.itemAppear(index),
                    onClick = { onOpenProfile(f.id) },
                ) {
                    Button(
                        onClick = { vm.accept(f.id) { app.refreshFriends() } },
                        shape = NovaCorners.button,
                        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                    ) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                        Text("accept", Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    FilledTonalIconButton(
                        onClick = { vm.remove(f.id) { app.refreshFriends() } },
                        shape = NovaCorners.button,
                    ) {
                        Icon(Icons.Outlined.Close, "decline", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (home.outgoing.isNotEmpty()) {
            item {
                NovaSectionLabel(
                    "sent · ${home.outgoing.size}",
                    modifier = Modifier.padding(start = Spacing.m, top = Spacing.m),
                )
            }
            itemsIndexed(home.outgoing, key = { _, f -> "out-${f.id}" }) { index, f ->
                NovaFriendCard(
                    f = f,
                    avatar = app.mediaUrl(f.avatar_key),
                    modifier = Modifier.itemAppear(index),
                    onClick = { onOpenProfile(f.id) },
                ) {
                    Row(
                        Modifier
                            .novaSurface(NovaCorners.chip, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary)
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("pending", Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Expressive friend card: lifted-top gradient surface with a lit hairline rim
 *  (accented cyan when online), a 56dp avatar wrapped in a breathing cyan
 *  presence ring + soft halo, bold two-line identity, and a prominent action row
 *  underneath. Depth comes from [novaSurface]; the list-level shadow is skipped
 *  per-row for scroll perf, the lit rim + halo carry the elevation cue instead. */
@Composable
private fun NovaFriendCard(
    f: FriendDto,
    avatar: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val name = (f.display_name ?: f.username)
    val online = presence(f.last_online)
    val cyan = MaterialTheme.colorScheme.secondary
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            // lit rim brightens to cyan when the friend is online (presence cue)
            .novaSurface(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, if (online) cyan else accent, accented = online)
            .clickableScale(pressedScale = 0.98f, onClick = onClick)
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NovaAvatarWithPresence(name = name, avatar = avatar, sharedKey = "avatar-${f.id}", online = online, ringColor = cyan)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    name.lowercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // cyan "online" is the signature dual-accent presence beat
                    if (online) "online now" else (f.status_text?.lowercase() ?: "@${f.username}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (online) cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

/** A 56dp avatar wrapped, when [online], in a soft cyan halo + a gently breathing
 *  presence ring — the "living presence" cue. Reduced-motion-safe: the pulse
 *  helper returns a constant so the ring is simply steady. */
@Composable
private fun NovaAvatarWithPresence(
    name: String,
    avatar: String?,
    sharedKey: String,
    online: Boolean,
    ringColor: androidx.compose.ui.graphics.Color,
) {
    if (!online) {
        Avatar(name, avatar, 56.dp, sharedKey = sharedKey)
        return
    }
    val pulse = rememberNovaPulse(periodMillis = 2600)
    // 0.6..1.0 ring alpha and 2.0..3.0dp width breathing
    val ringAlpha = 0.55f + 0.45f * pulse
    val ringWidth = (2.0f + 1.0f * pulse).dp
    Box(
        Modifier
            .novaHalo(ringColor, alpha = 0.10f + 0.10f * pulse),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .border(ringWidth, ringColor.copy(alpha = ringAlpha), CircleShape)
                .padding(3.dp),
        ) {
            Avatar(name, avatar, 56.dp, sharedKey = sharedKey)
        }
    }
}

// --- NOVA (ported from the -exp 2nd experiment) ----------------------------------
// The ACTUAL 2nd-experiment layout, distinct from Galaxy: flatter surfaces, a
// status-bar-padded hero header, a solid-primary segmented indicator (no gradient
// glow), and friend/request CARDS on plain filled surfaces with a mint presence
// ring. Renamed with an `Exp` prefix so it does not clash with the Galaxy `Nova*`
// composables above; adapted to exp3's flat Nova primitives / MaterialTheme.

/** Hero header: big display title with a live stat line and a rounded add button. */
@Composable
private fun ExpFriendsHeader(
    friendCount: Int,
    onlineCount: Int,
    requestCount: Int,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "friends",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val stat = buildString {
                append("$friendCount friend").append(if (friendCount == 1) "" else "s")
                if (onlineCount > 0) append(" · $onlineCount online")
                if (requestCount > 0) append(" · $requestCount request").append(if (requestCount == 1) "" else "s")
            }
            Text(
                stat,
                style = MaterialTheme.typography.bodyMedium,
                color = if (requestCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(NovaCorners.iconBadge)
                .background(MaterialTheme.colorScheme.primary)
                .clickableScale(pressedScale = 0.9f, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PersonAdd, "add", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Bold segmented control with per-segment counts and a bouncy sliding indicator. */
@Composable
private fun ExpSegmentedTabs(
    friendsLabel: String,
    friendsCount: Int,
    requestsLabel: String,
    requestsCount: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(friendsLabel to friendsCount, requestsLabel to requestsCount)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(NovaCorners.group)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.xs),
    ) {
        val segW = maxWidth / 2
        val spec: FiniteAnimationSpec<Dp> = PigeonMotion.bouncy()
        val x by animateDpAsState(segW * selected, spec, label = "expTabX")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .clip(NovaCorners.chip)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, (label, count) ->
                val active = i == selected
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(NovaCorners.chip)
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (count > 0) {
                        Spacer(Modifier.width(Spacing.xs))
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                                .padding(horizontal = Spacing.s, vertical = 1.dp),
                        ) {
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpFriendsTab(
    app: AppViewModel,
    vm: FriendsViewModel,
    onOpenChat: (String, String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    if (home.friends.isEmpty()) {
        Empty("no friends yet", "accept a request or add someone", Icons.Outlined.PersonAdd)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        itemsIndexed(home.friends, key = { _, f -> f.id }) { index, f ->
            ExpFriendCard(
                f = f,
                avatar = app.mediaUrl(f.avatar_key),
                modifier = Modifier.itemAppear(index),
                onClick = { onOpenProfile(f.id) },
            ) {
                FilledTonalButton(
                    onClick = { vm.openDm(f.id) { ch -> onOpenChat(ch, f.display_name ?: f.username) } },
                    shape = NovaCorners.button,
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("message", Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ExpRequestsTab(
    app: AppViewModel,
    vm: FriendsViewModel,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    if (home.incoming.isEmpty() && home.outgoing.isEmpty()) {
        Empty("no pending requests", "requests you send or receive show up here", Icons.Outlined.HourglassEmpty)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (home.incoming.isNotEmpty()) {
            item { ListSectionHeader("incoming · ${home.incoming.size}") }
            itemsIndexed(home.incoming, key = { _, f -> "in-${f.id}" }) { index, f ->
                ExpFriendCard(
                    f = f,
                    avatar = app.mediaUrl(f.avatar_key),
                    modifier = Modifier.itemAppear(index),
                    onClick = { onOpenProfile(f.id) },
                ) {
                    Button(
                        onClick = { vm.accept(f.id) { app.refreshFriends() } },
                        shape = NovaCorners.button,
                        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                    ) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                        Text("accept", Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    FilledTonalIconButton(
                        onClick = { vm.remove(f.id) { app.refreshFriends() } },
                        shape = NovaCorners.button,
                    ) {
                        Icon(Icons.Outlined.Close, "decline", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (home.outgoing.isNotEmpty()) {
            item { ListSectionHeader("sent · ${home.outgoing.size}") }
            itemsIndexed(home.outgoing, key = { _, f -> "out-${f.id}" }) { index, f ->
                ExpFriendCard(
                    f = f,
                    avatar = app.mediaUrl(f.avatar_key),
                    modifier = Modifier.itemAppear(index),
                    onClick = { onOpenProfile(f.id) },
                ) {
                    Row(
                        Modifier
                            .clip(NovaCorners.chip)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("pending", Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Expressive friend card: rounded filled surface, 56dp avatar with a cyan
 *  presence ring, bold two-line identity, and an action row underneath the
 *  identity block so buttons are prominent and full-width-ish. */
@Composable
private fun ExpFriendCard(
    f: FriendDto,
    avatar: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val name = (f.display_name ?: f.username)
    Column(
        modifier
            .fillMaxWidth()
            .clip(NovaCorners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickableScale(pressedScale = 0.98f, onClick = onClick)
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.then(
                    if (presence(f.last_online)) Modifier.border(2.5.dp, MaterialTheme.colorScheme.secondary, CircleShape).padding(3.dp)
                    else Modifier,
                ),
            ) {
                Avatar(name, avatar, 56.dp, sharedKey = "avatar-${f.id}")
            }
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    name.lowercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    f.status_text?.lowercase() ?: "@${f.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}
