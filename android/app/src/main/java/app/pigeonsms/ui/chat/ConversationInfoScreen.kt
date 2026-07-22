package app.pigeonsms.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pigeonsms.db.MessageEntity
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.presence
import app.pigeonsms.ui.util.smartTime
import coil.compose.AsyncImage

@Composable
fun ConversationInfoScreen(
    vm: ChatViewModel,
    title: String,
    avatarKey: String? = null,
    onDismiss: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    val media by vm.media.collectAsState()
    val ui by vm.ui.collectAsState()
    val mediaItems = remember(media) {
        media.mapNotNull { message ->
            val key = message.attachmentKey ?: return@mapNotNull null
            val type = message.attachmentType ?: return@mapNotNull null
            ConversationMedia(message.id, vm.mediaUrl(key), message.attachmentName, type)
        }
    }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var tab by rememberSaveable { mutableStateOf(InfoTab.Media) }

    // stale results shouldn't greet the next open
    DisposableEffect(Unit) { onDispose { vm.clearLocalSearch() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back to chat", tint = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.weight(1f).padding(horizontal = Spacing.s)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "conversation info",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ConversationProfileHeader(
                title = title,
                model = avatarKey?.let(vm::mediaUrl),
                lastOnline = ui.peerLastOnline,
            )

            InfoTabSelector(selected = tab, onSelect = { tab = it })

            Crossfade(targetState = tab, animationSpec = tween(180), label = "infoTab") { current ->
                when (current) {
                    InfoTab.Media -> MediaGrid(
                        items = mediaItems,
                        onOpen = { index -> viewerIndex = index },
                    )
                    InfoTab.Search -> SearchPane(
                        results = ui.localSearchResults,
                        searching = ui.localSearching,
                        onQuery = vm::localSearch,
                        onOpen = onJumpToMessage,
                    )
                }
            }
        }
    }

    viewerIndex?.let { index ->
        ConversationMediaViewer(mediaItems, index) { viewerIndex = null }
    }
}

@Composable
private fun ConversationProfileHeader(title: String, model: Any?, lastOnline: Long?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(name = title, model = model, size = 92.dp)
        Spacer(Modifier.height(Spacing.s))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        lastOnline?.let { last ->
            val online = presence(last)
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                if (online) "online" else "offline",
                style = MaterialTheme.typography.labelMedium,
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class InfoTab(val label: String) { Media("media"), Search("search") }

@Composable
private fun InfoTabSelector(selected: InfoTab, onSelect: (InfoTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        InfoTab.entries.forEach { tab ->
            val active = tab == selected
            val container by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                tween(160),
                label = "tabColor",
            )
            val scale by animateFloatAsState(if (active) 1f else 0.96f, PigeonMotion.snappy(), label = "tabScale")
            Surface(
                onClick = { onSelect(tab) },
                shape = Corners.button,
                color = container,
                modifier = Modifier.weight(1f)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .semantics {
                        role = Role.Tab
                        contentDescription = "${tab.label} tab"
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.m),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (tab == InfoTab.Media) Icons.Outlined.Image else Icons.Outlined.Search,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(items: List<ConversationMedia>, onOpen: (Int) -> Unit) {
    if (items.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Image, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.s))
            Text(
                "no photos or videos yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "media shared in this chat shows up here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        itemsIndexed(items, key = { _, item -> item.messageId }) { index, item ->
            val video = item.type.isConversationVideo()
            Box(
                Modifier.aspectRatio(1f)
                    .clip(Corners.chip)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpen(index) }
                    .semantics {
                        role = Role.Button
                        contentDescription = item.name ?: if (video) "video ${index + 1}" else "photo ${index + 1}"
                    },
            ) {
                if (video) {
                    AsyncImage(
                        model = item.url,
                        imageLoader = VideoFrames.loader(context),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model = item.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (video) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.Center).size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPane(
    results: List<MessageEntity>,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onQuery(it) },
            placeholder = { Text("search this conversation") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; onQuery("") }) {
                        Icon(Icons.Outlined.Close, "clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = Corners.input,
        )
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!searching && results.isEmpty()) {
            Text(
                if (query.trim().length < 2) "type at least two characters to search downloaded history"
                else "no matches in downloaded history",
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            items(results, key = { it.id }) { message ->
                Surface(
                    onClick = { onOpen(message.id) },
                    shape = Corners.chip,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                message.authorName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                smartTime(message.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            message.content.ifBlank { message.attachmentName ?: "attachment" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
