package app.pigeonsms.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.PigeonColors
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.DmDto
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.ScreenHeader
import app.pigeonsms.ui.util.SkeletonList
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.presence
import app.pigeonsms.ui.util.smartTime
import kotlinx.coroutines.delay

// home / friends / spaces / forum stay calm: plain full-bleed surfaces, spacing-only
// separation, one appear animation, one unread pill.

internal fun Modifier.itemAppear(index: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    if (reduced) return@composed this
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 24L)
        shown = true
    }
    val settle by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = PigeonMotion.smooth(),
        label = "itemAppear",
    )
    graphicsLayer {
        alpha = settle
        translationY = (1f - settle) * 12.dp.toPx()
    }
}

@Composable
internal fun UnreadPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
internal fun ListSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.lowercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.xl, bottom = Spacing.s, start = Spacing.l, end = Spacing.l),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    app: AppViewModel,
    onOpenChat: (DmDto) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val home by app.home.collectAsState()
    LaunchedEffect(Unit) { app.refreshDms() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("messages")
        if (home.dmsLoading && home.dms.isEmpty()) SkeletonList()
        else if (home.dmsError != null && home.dms.isEmpty()) ErrorState(home.dmsError!!, app::refreshDms)
        else if (home.dms.isEmpty()) Empty("no messages yet", "start a chat from friends")
        else PullToRefreshBox(isRefreshing = home.dmsLoading, onRefresh = { app.refreshDms() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = Spacing.s),
            ) {
                itemsIndexed(home.dms, key = { _, dm -> dm.channel_id }) { index, dm ->
                    DmRow(
                        dm = dm,
                        avatarUrl = app.mediaUrl(dm.peer.avatar_key),
                        modifier = Modifier.animateItem().itemAppear(index),
                        onOpen = { onOpenChat(dm) },
                        onOpenProfile = { onOpenProfile(dm.peer.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DmRow(
    dm: DmDto,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val name = dm.peer.display_name ?: dm.peer.username
    val online = presence(dm.peer.last_online)
    val unread = dm.unread > 0
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickableScale(pressedScale = 0.98f, onClick = onOpen)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .then(if (online) Modifier.border(2.dp, PigeonColors.Mint, CircleShape).padding(3.dp) else Modifier)
                .clip(CircleShape)
                .clickable(onClick = onOpenProfile)
                .semantics {
                    role = Role.Button
                    contentDescription = "open $name profile"
                },
        ) {
            Avatar(name, avatarUrl, 52.dp, sharedKey = "chat-avatar-${dm.channel_id}")
        }
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                name.lowercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                dm.last_message?.let { if (it.deleted) "message deleted" else it.content } ?: "say hi 👋",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.Medium else null,
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.s))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            dm.last_message?.let {
                Text(
                    smartTime(it.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unread) UnreadPill(dm.unread)
        }
    }
}
