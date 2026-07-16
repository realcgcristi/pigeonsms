package app.pigeonsms.ui.friends

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.Dp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.PigeonColors
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
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

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("friends") {
            IconButton(onClick = { addOpen = true }) { Icon(Icons.Outlined.PersonAdd, "add", tint = MaterialTheme.colorScheme.primary) }
        }
        if (home.friendsLoading && nothingYet) {
            SkeletonList()
        } else if (home.friendsError != null && nothingYet) {
            ErrorState(home.friendsError!!, app::refreshFriends)
        } else if (nothingYet) {
            Empty("no friends yet", "add someone by their username", Icons.Outlined.PersonAdd)
        } else {
            PillTabs(
                options = listOf("friends", "requests"),
                selected = tab,
                onSelect = { tab = it },
                badgeCount = home.incoming.size,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
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
                    if (page == 0) FriendsTab(app, vm, onOpenChat, onOpenProfile)
                    else RequestsTab(app, vm, onOpenProfile)
                }
            }
        }
    }

    if (addOpen) {
        var query by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { if (!adding) { addOpen = false; vm.clearMessage() } }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(Spacing.l).padding(bottom = Spacing.xl)) {
                Text("add a friend", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("they'll get a request to accept", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                Spacer(Modifier.height(Spacing.l))
                OutlinedTextField(query, { query = it.take(32); vm.clearMessage() }, label = { Text("username") }, enabled = !adding, singleLine = true, shape = Corners.input, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send))
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s)) }
                Spacer(Modifier.height(Spacing.l))
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
