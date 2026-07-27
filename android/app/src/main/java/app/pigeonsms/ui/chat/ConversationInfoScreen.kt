package app.pigeonsms.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pigeonsms.db.MessageEntity
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.heroAppear
import app.pigeonsms.design.theme.novaAuroraBackground
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaGlow
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.design.theme.novaSurface
import app.pigeonsms.design.theme.rememberNovaPulse
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.presence
import app.pigeonsms.ui.util.smartTime
import coil.compose.AsyncImage

/**
 * WhatsApp-style "conversation info": every image/video shared in the chat as a
 * 3-column grid, plus offline message search over the locally cached history.
 * Presented as a fullscreen dialog on top of the chat.
 */
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

    val nova = LocalExperimentalRedesign.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val shell = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .let { base ->
                // aurora mesh behind the whole (non-scrolling) info shell — the
                // "space-indigo canvas" the Nova brief calls for.
                if (nova) base.novaAuroraBackground(MaterialTheme.colorScheme.primary, animate = true) else base
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
        Column(shell) {
            if (nova) NovaInfoTopBar(title = title, onDismiss = onDismiss)
            else ClassicInfoTopBar(title = title, onDismiss = onDismiss)

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

/** Classic top bar — plain back button + stacked title / subtitle. Unchanged. */
@Composable
private fun ClassicInfoTopBar(title: String, onDismiss: () -> Unit) {
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
}

/** Nova top bar — a rounded icon-badge back affordance (lit rim + spring press),
 *  a louder title, and a cyan-tracked "conversation info" subline. */
@Composable
private fun NovaInfoTopBar(title: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovaBackBadge(onClick = onDismiss)
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m).heroAppear()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "CONVERSATION INFO",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** Rounded-square Nova back button with a lit rim + spring press. */
@Composable
private fun NovaBackBadge(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, NovaMotion.press(), label = "backPress")
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(44.dp)
            .novaElevation(NovaCorners.iconBadge, MaterialTheme.colorScheme.surfaceContainerHigh, accent, accented = true)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(bounded = true), onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "back to chat" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = accent, modifier = Modifier.size(22.dp))
    }
}

/**
 * Centered profile header at the top of the conversation-info screen: large
 * avatar + conversation name + presence. [model] is the pfp image (null falls
 * back to the colored-initial avatar); [lastOnline] drives the online line
 * (null for group channels — the status line is then omitted).
 */
@Composable
private fun ConversationProfileHeader(title: String, model: Any?, lastOnline: Long?) {
    if (LocalExperimentalRedesign.current) {
        NovaProfileHeader(title, model, lastOnline)
        return
    }
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

/**
 * Nova profile header — the hero of this screen. A soft accent halo lifts the
 * avatar off the fold; when the peer is online a cyan presence ring breathes
 * around it (reduced-motion aware). The name reveals via [heroAppear] and
 * presence reads in cyan to reinforce the iris+cyan pair.
 */
@Composable
private fun NovaProfileHeader(title: String, model: Any?, lastOnline: Long?) {
    val accent = MaterialTheme.colorScheme.primary
    val cyan = MaterialTheme.colorScheme.secondary
    val online = lastOnline?.let { presence(it) } == true
    val pulse = rememberNovaPulse(periodMillis = 2400)

    Column(
        Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // soft accent halo washed under the avatar so it lifts off the canvas
            Box(
                Modifier
                    .size(132.dp)
                    .novaHalo(if (online) cyan else accent, alpha = NovaDepth.glowSoft),
            )
            val ringColor = if (online) cyan else accent
            // breathing presence ring — cyan when online, quieter accent otherwise
            val ringAlpha = if (online) 0.55f + 0.45f * pulse else 0.32f
            val ringWidth = (if (online) 2.5f else 2f) + (if (online) 1f * pulse else 0f)
            Box(
                Modifier
                    .size(104.dp)
                    .border(
                        width = ringWidth.dp,
                        brush = Brush.sweepGradient(
                            listOf(ringColor.copy(alpha = ringAlpha), accent.copy(alpha = ringAlpha * 0.6f), ringColor.copy(alpha = ringAlpha)),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(name = title, model = model, size = 92.dp)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heroAppear(delayMillis = 40),
        )
        lastOnline?.let {
            Spacer(Modifier.height(Spacing.xs))
            // presence chip: cyan "online" pill / muted "offline"
            if (online) {
                Row(
                    Modifier
                        .clip(NovaCorners.chip)
                        .background(cyan.copy(alpha = 0.14f))
                        .padding(horizontal = Spacing.m, vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(cyan))
                    Text(
                        "online",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cyan,
                    )
                }
            } else {
                Text(
                    "offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class InfoTab(val label: String) { Media("media"), Search("search") }

@Composable
private fun InfoTabSelector(selected: InfoTab, onSelect: (InfoTab) -> Unit) {
    if (LocalExperimentalRedesign.current) {
        NovaInfoTabSelector(selected, onSelect)
        return
    }
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

/**
 * Nova segmented tab pills. The selected pill fills with the iris→cyan CTA
 * gradient (dual-accent signature) and casts an accent glow; the unselected pill
 * is a lit-rim Nova surface. Both press with a spring.
 */
@Composable
private fun NovaInfoTabSelector(selected: InfoTab, onSelect: (InfoTab) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        InfoTab.entries.forEach { tab ->
            val active = tab == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(
                if (pressed) 0.95f else if (active) 1f else 0.98f,
                NovaMotion.press(),
                label = "novaTabScale",
            )
            val shape = NovaCorners.button
            val fill = if (active) {
                Modifier
                    .shadow(
                        elevation = NovaDepth.raisedElevation,
                        shape = shape,
                        clip = false,
                        spotColor = accent.copy(alpha = NovaDepth.glowStrong),
                        ambientColor = Color.Black,
                    )
                    .clip(shape)
                    .background(Brush.horizontalGradient(NovaGradients.cta(accent)))
            } else {
                Modifier.novaSurface(shape, MaterialTheme.colorScheme.surfaceContainerHigh, accent)
            }
            val ink = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier
                    .weight(1f)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .then(fill)
                    .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = { onSelect(tab) })
                    .padding(vertical = Spacing.m)
                    .semantics { role = Role.Tab; contentDescription = "${tab.label} tab" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (tab == InfoTab.Media) Icons.Outlined.Image else Icons.Outlined.Search,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = ink,
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                )
            }
        }
    }
}

@Composable
private fun MediaGrid(items: List<ConversationMedia>, onOpen: (Int) -> Unit) {
    val nova = LocalExperimentalRedesign.current
    if (items.isEmpty()) {
        if (nova) {
            NovaMediaEmpty()
        } else {
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
        }
        return
    }
    val context = LocalContext.current
    val tileShape = if (nova) NovaCorners.chip else Corners.chip
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(if (nova) Spacing.xs else Spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(if (nova) Spacing.xs else Spacing.xxs),
    ) {
        itemsIndexed(items, key = { _, item -> item.messageId }) { index, item ->
            val video = item.type.isConversationVideo()
            val tile = if (nova) {
                Modifier.aspectRatio(1f)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, Color.White.copy(alpha = NovaDepth.rimTop), tileShape)
            } else {
                Modifier.aspectRatio(1f)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Box(
                tile
                    .then(if (nova) Modifier.novaTileAppear(index) else Modifier)
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
                    if (nova) {
                        Box(
                            Modifier.align(Alignment.Center).size(38.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(NovaGradients.cta(MaterialTheme.colorScheme.primary))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                        }
                    } else {
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
}

/** One-shot spring scale-in for a Nova media tile, lightly staggered by grid
 *  position so the grid assembles instead of popping in flat. */
@Composable
private fun Modifier.novaTileAppear(index: Int): Modifier {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index % 9) * 22L)
        shown = true
    }
    val p by animateFloatAsState(if (shown || reduced) 1f else 0f, NovaMotion.pop(), label = "tileAppear")
    return this.graphicsLayer {
        alpha = p
        val s = 0.82f + 0.18f * p
        scaleX = s
        scaleY = s
    }
}

/** Nova empty-media state — accent-washed disc + halo glow so it reads designed,
 *  not broken. Mirrors the shared EmptyState language with a breathing glow. */
@Composable
private fun NovaMediaEmpty() {
    val accent = MaterialTheme.colorScheme.primary
    val pulse = rememberNovaPulse(periodMillis = 3000)
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .novaHalo(accent, alpha = 0.10f + 0.10f * pulse),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.10f))))
                    .border(1.dp, accent.copy(alpha = NovaDepth.rimAccent), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Image, null, modifier = Modifier.size(32.dp), tint = accent)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            "no photos or videos yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            "media shared in this chat shows up here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchPane(
    results: List<MessageEntity>,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val nova = LocalExperimentalRedesign.current
    var query by rememberSaveable { mutableStateOf("") }
    val fieldSource = remember { MutableInteractionSource() }
    val focused by fieldSource.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        if (nova) {
            // floating Nova search pill: lit rim + shadow, focus wakes the rim.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; onQuery(it) },
                placeholder = { Text("search this conversation") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = if (focused) accent else MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; onQuery("") }) {
                            Icon(Icons.Outlined.Close, "clear search")
                        }
                    }
                },
                interactionSource = fieldSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs)
                    .novaElevation(NovaCorners.input, MaterialTheme.colorScheme.surfaceContainerHigh, accent, accented = focused)
                    .novaGlow(NovaCorners.input, accent, active = focused),
                singleLine = true,
                shape = NovaCorners.input,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
        } else {
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
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (searching) {
                if (nova) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().clip(CircleShape),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
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
                if (nova) {
                    NovaSearchResult(message = message, onOpen = onOpen)
                } else {
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
}

/** Nova search result card — lit-rim Nova surface with a spring press, an iris
 *  author name and a cyan timestamp so the dual-accent pair reads on every row. */
@Composable
private fun NovaSearchResult(message: MessageEntity, onOpen: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, NovaMotion.press(), label = "resultPress")
    val shape = NovaCorners.card
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .novaSurface(shape, MaterialTheme.colorScheme.surfaceContainerHigh, accent)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = { onOpen(message.id) })
            .padding(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.authorName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                smartTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            message.content.ifBlank { message.attachmentName ?: "attachment" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
