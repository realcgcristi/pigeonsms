package app.pigeonsms.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.glassCard
import app.pigeonsms.ui.util.glassPanel
import app.pigeonsms.ui.util.pressScale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pigeonsms.db.MessageEntity
import app.pigeonsms.data.ChatAppearance
import app.pigeonsms.data.ChatAppearanceStore
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.PigeonAccents
import app.pigeonsms.design.theme.PigeonWallpapers
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.accentByKey
import app.pigeonsms.design.theme.rememberWallpaperBrush
import app.pigeonsms.design.theme.wallpaperByKey
import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Link
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.compose.AsyncImage
import app.pigeonsms.network.ReactionDto
import app.pigeonsms.network.RevisionDto
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.dayLabel
import app.pigeonsms.ui.util.smartTime
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.outlined.GraphicEq
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Badge
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

private const val MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L
private val chatJson = Json { ignoreUnknownKeys = true }
private val reactionChoices = listOf("👍", "❤️", "😂", "🎉", "🐦", "🔥")

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    channelId: String,
    title: String,
    avatarKey: String? = null,
    selfId: String,
    selfName: String,
    isAdmin: Boolean,
    isSpace: Boolean = false,
    sessionToken: String? = null,
    typingEvents: Flow<Pair<String, String>>,
    onBack: () -> Unit,
    onActive: (String?) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val vmAppContext = LocalContext.current.applicationContext
    val vm: ChatViewModel = pigeonVm(key = "chat-$channelId") { container, _ ->
        ChatViewModel(
            container.chatRepository, channelId, selfId, selfName, isAdmin,
            app.pigeonsms.db.PigeonDatabase.get(vmAppContext).messages(),
            container.socialRepository, isSpace,
        )
    }
    val messages by vm.messages.collectAsState()
    val ui by vm.ui.collectAsState()
    val mediaItems = remember(messages) {
        messages.mapNotNull { message ->
            val key = message.attachmentKey ?: return@mapNotNull null
            val type = message.attachmentType ?: return@mapNotNull null
            if (type.startsWith("image/") || type.startsWith("video/")) {
                ConversationMedia(message.id, vm.mediaUrl(key), message.attachmentName, type)
            } else null
        }
    }
    var mediaViewerIndex by remember(channelId) { mutableStateOf<Int?>(null) }
    var infoOpen by remember(channelId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val replyNavigationScope = rememberCoroutineScope()
    val messageById = remember(messages) { messages.associateBy(MessageEntity::id) }
    var highlightedMessageId by remember(channelId) { mutableStateOf<String?>(null) }
    val lastOwnId = remember(messages) {
        messages.lastOrNull { vm.isOwn(it) && !it.deleted && it.state == "SENT" }?.id
    }
    var positioned by remember(channelId) { mutableStateOf(false) }
    var lastReadSeq by remember(channelId) { mutableStateOf(0L) }
    val currentOnActive by rememberUpdatedState(onActive)
    val imeVisible = WindowInsets.isImeVisible
    val appCtx = LocalContext.current.applicationContext
    val themePrefs by remember(appCtx) { (appCtx as app.pigeonsms.PigeonApp).container.themeStore.prefs }
        .collectAsState(initial = app.pigeonsms.data.ThemePrefs())
    val chatHaze = remember { HazeState() }
    val barInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    var unseenWhileUp by remember(channelId) { mutableStateOf(0) }
    var prevMsgCount by remember(channelId) { mutableStateOf(0) }

    DisposableEffect(channelId) {
        currentOnActive(channelId)
        onDispose { currentOnActive(null) }
    }

    LaunchedEffect(channelId, typingEvents) {
        typingEvents.collect { (eventChannelId, username) ->
            if (eventChannelId == channelId) vm.onTypingEvent(username)
        }
    }

    LaunchedEffect(messages.lastOrNull()?.id, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastIndex = messages.lastIndex
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = visibleLast >= lastIndex - 2
        when {
            !positioned -> listState.scrollToItem(lastIndex)
            nearBottom || vm.isOwn(messages.last()) -> listState.animateScrollToItem(lastIndex)
            else -> if (messages.size > prevMsgCount && !vm.isOwn(messages.last())) {
                unseenWhileUp += messages.size - prevMsgCount
            }
        }
        prevMsgCount = messages.size
        positioned = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollForward }
            .distinctUntilChanged()
            .filter { it }
            .collect { unseenWhileUp = 0 }
    }

    LaunchedEffect(listState, positioned, messages) {
        val latestReadable = messages.lastOrNull {
            it.seq > 0 && it.seq < Long.MAX_VALUE / 4
        } ?: return@LaunchedEffect
        if (!positioned) return@LaunchedEffect

        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.key == latestReadable.id }
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (latestReadable.seq > lastReadSeq && themePrefs.readReceipts) {
                    lastReadSeq = latestReadable.seq
                    vm.markRead(latestReadable.seq)
                }
            }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            positioned && messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.loadOlder() }
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    val appearanceContext = LocalContext.current
    val appearanceStore = remember { ChatAppearanceStore(appearanceContext.applicationContext) }
    val appearance by appearanceStore.appearance(channelId).collectAsState(initial = ChatAppearance(null, null))
    val appearanceScope = rememberCoroutineScope()
    var showAppearance by remember { mutableStateOf(false) }
    var showCreatePoll by remember { mutableStateOf(false) }
    var showCreateEvent by remember { mutableStateOf(false) }
    var callVideo by remember { mutableStateOf<Boolean?>(null) }
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val requested = pendingCallVideo
        if (requested != null && grants[android.Manifest.permission.RECORD_AUDIO] == true &&
            (!requested || grants[android.Manifest.permission.CAMERA] == true)
        ) callVideo = requested
        pendingCallVideo = null
    }
    fun startCall(video: Boolean) {
        val missing = buildList {
            if (androidx.core.content.ContextCompat.checkSelfPermission(appCtx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(android.Manifest.permission.RECORD_AUDIO)
            if (video && androidx.core.content.ContextCompat.checkSelfPermission(appCtx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(android.Manifest.permission.CAMERA)
        }
        if (missing.isEmpty()) callVideo = video else {
            pendingCallVideo = video
            callPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    ChatAccent(appearance.accent) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatWallpaper(appearance.wallpaper)
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) chatLayout@ {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().hazeSource(chatHaze)) {
            when {
                messages.isEmpty() && ui.initialLoading -> {
                    LoadingIndicator(modifier = Modifier.size(48.dp).align(Alignment.Center))
                }
                messages.isEmpty() -> EmptyChatState(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.m,
                        top = barInset + Spacing.s,
                        end = Spacing.m,
                        bottom = Spacing.m,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    if (ui.loadingOlder) {
                        item(key = "older-loading", contentType = "progress") {
                            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                                LoadingIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                    itemsIndexed(
                        items = messages,
                        key = { _, message -> message.id },
                        contentType = { _, message -> if (vm.isOwn(message)) "own" else "other" },
                    ) { index, message ->
                        val previous = messages.getOrNull(index - 1)
                        val next = messages.getOrNull(index + 1)
                        val newDay = previous == null || dayLabel(previous.createdAt) != dayLabel(message.createdAt)
                        val grouped = !newDay &&
                            previous?.authorId == message.authorId &&
                            message.createdAt - previous.createdAt < 5 * 60_000
                        val groupedBelow = next != null &&
                            next.authorId == message.authorId &&
                            next.createdAt - message.createdAt < 5 * 60_000 &&
                            dayLabel(next.createdAt) == dayLabel(message.createdAt)
                        Column(Modifier.animateItem()) {
                        if (newDay) DaySeparator(dayLabel(message.createdAt))
                        MessageBubble(
                            message = message,
                            reply = message.replyTo?.let(messageById::get),
                            self = vm.isOwn(message),
                            grouped = grouped,
                            isAdmin = ui.isAdmin,
                            busy = message.id in ui.busyMessageIds,
                            pinned = message.id in ui.pinnedMessageIds,
                            highlighted = message.id == highlightedMessageId,
                            mediaUrl = vm::mediaUrl,
                            onOpenProfile = onOpenProfile,
                            onNavigateToReply = { replyId ->
                                val targetIndex = messages.indexOfFirst { it.id == replyId }
                                if (targetIndex < 0) {
                                    vm.reportError("original message isn't available in loaded history")
                                } else {
                                    replyNavigationScope.launch {
                                        val leadingItems = if (ui.loadingOlder) 1 else 0
                                        listState.animateScrollToItem(targetIndex + leadingItems)
                                        highlightedMessageId = replyId
                                        delay(1_600)
                                        if (highlightedMessageId == replyId) highlightedMessageId = null
                                    }
                                }
                            },
                            onReply = { vm.setReply(message) },
                            onEdit = { vm.setEditing(message) },
                            onDelete = { vm.delete(message) },
                            onReact = { emoji, on -> vm.toggleReaction(message, emoji, on) },
                            onPin = { on -> vm.pin(message, on) },
                            onRetry = { vm.retry(message) },
                            onOpenMedia = { key -> mediaViewerIndex = mediaItems.indexOfFirst { it.messageId == message.id }.takeIf { it >= 0 } },
                            onSuperPin = { on -> vm.setSuperPin(message, on) },
                            superPinned = message.id == ui.superPin?.id,
                            onVote = { optionId -> vm.votePoll(message, optionId) },
                            seen = message.id == lastOwnId && ui.peerReadSeq >= message.seq,
                            groupedBelow = groupedBelow,
                        )
                        }
                    }
                }
            }
            }
            Column(Modifier.align(Alignment.TopCenter)) {
                ChatTopBar(title, avatarKey, channelId, vm::mediaUrl, chatHaze, onBack, onSearch = vm::openSearch, onPins = vm::loadPins, onAppearance = { showAppearance = true }, onCall = ::startCall, onInfo = { infoOpen = true })
                ui.superPin?.let { superPin ->
                    val superIndex = messages.indexOfFirst { it.id == superPin.id }
                    SuperPinBanner(superPin, onDismiss = vm::dismissSuperPin) {
                        if (superIndex >= 0) appearanceScope.launch { listState.animateScrollToItem(superIndex + if (ui.loadingOlder) 1 else 0) }
                    }
                }
                AnimatedVisibility(
                    visible = ui.error != null,
                    enter = fadeIn(tween(140)) + slideInVertically(tween(140)) { -it },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it },
                ) {
                    ErrorBanner(message = ui.error.orEmpty(), onRetry = vm::refresh, onDismiss = vm::clearError)
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = listState.canScrollForward && messages.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.m),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box {
                    FloatingActionButton(
                        onClick = { appearanceScope.launch { listState.animateScrollToItem(messages.lastIndex) } },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp),
                    ) { Icon(Icons.Outlined.KeyboardArrowDown, "scroll to latest") }
                    if (unseenWhileUp > 0) {
                        Badge(
                            modifier = Modifier.align(Alignment.TopEnd),
                            containerColor = MaterialTheme.colorScheme.error,
                        ) { Text("$unseenWhileUp") }
                    }
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = Spacing.l),
            contentAlignment = Alignment.CenterStart,
        ) {
            this@chatLayout.AnimatedVisibility(
                visible = ui.typingUser != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .glassCard(
                                RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp),
                                tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                            .padding(horizontal = Spacing.m, vertical = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        val pulse = rememberInfiniteTransition(label = "typingDots")
                        repeat(3) { i ->
                            val dotAlpha by pulse.animateFloat(
                                initialValue = 0.25f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse, StartOffset(i * 140)),
                                label = "dot$i",
                            )
                            Box(
                                Modifier.size(7.dp)
                                    .graphicsLayer { alpha = dotAlpha }
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                            )
                        }
                    }
                    Text(
                        "${ui.typingUser.orEmpty()} is typing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
            }
        }

        Composer(
            ui = ui,
            isSpace = isSpace,
            onSend = vm::send,
            onTyping = vm::typing,
            onAttachment = vm::sendAttachment,
            onAttachmentError = vm::reportError,
            onClearReply = { vm.setReply(null) },
            onClearEdit = { vm.setEditing(null) },
            onMentionLookup = vm::loadMentionCandidates,
            mediaUrl = vm::mediaUrl,
            onCreatePoll = { showCreatePoll = true },
            onCreateEvent = { showCreateEvent = true },
        )
        }
    }
    }

    if (showCreatePoll) {
        CreatePollDialog(
            onDismiss = { showCreatePoll = false },
            onCreate = { question, options, anonymous ->
                showCreatePoll = false
                vm.sendPoll(question, options, anonymous)
            },
        )
    }
    if (showCreateEvent) {
        CreateEventDialog(
            onDismiss = { showCreateEvent = false },
            onCreate = { eventTitle, startsAt, endsAt, location, description ->
                showCreateEvent = false
                vm.sendEvent(eventTitle, startsAt, endsAt, location, description)
            },
        )
    }

    if (showAppearance) {
        ChatAppearanceSheet(
            current = appearance,
            onWallpaper = { key -> appearanceScope.launch { appearanceStore.setWallpaper(channelId, key) } },
            onAccent = { key -> appearanceScope.launch { appearanceStore.setAccent(channelId, key) } },
            onMute = { muted -> appearanceScope.launch { appearanceStore.setMuted(channelId, muted) } },
            onDismiss = { showAppearance = false },
        )
    }
    if (ui.searchOpen) SearchSheet(vm)
    if (ui.pinsOpen) {
        PinsSheet(vm) { messageId ->
            val targetIndex = messages.indexOfFirst { it.id == messageId }
            if (targetIndex < 0) {
                vm.reportError("pinned message isn't available in loaded history")
            } else {
                vm.closePins()
                replyNavigationScope.launch {
                    listState.animateScrollToItem(targetIndex + if (ui.loadingOlder) 1 else 0)
                    highlightedMessageId = messageId
                    delay(1_600)
                    if (highlightedMessageId == messageId) highlightedMessageId = null
                }
            }
        }
    }
    if (infoOpen) {
        ConversationInfoScreen(
            vm = vm,
            title = title,
            onDismiss = { infoOpen = false },
            onJumpToMessage = { messageId ->
                infoOpen = false
                val targetIndex = messages.indexOfFirst { it.id == messageId }
                if (targetIndex >= 0) {
                    replyNavigationScope.launch {
                        listState.animateScrollToItem(targetIndex + if (ui.loadingOlder) 1 else 0)
                        highlightedMessageId = messageId
                        delay(1_600)
                        if (highlightedMessageId == messageId) highlightedMessageId = null
                    }
                }
            },
        )
    }
    mediaViewerIndex?.let { index ->
        ConversationMediaViewer(mediaItems, index) { mediaViewerIndex = null }
    }
    callVideo?.let { video ->
        val token = sessionToken
        if (token != null) {
            CallScreenDialog(
                websocketUrl = "wss://api.pigeonsms.aldi.best/calls/${Uri.encode(channelId)}/ws?mode=${if (video) "video" else "voice"}&token=${Uri.encode(token)}",
                video = video,
                title = title,
                onDismiss = { callVideo = null },
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    avatarKey: String?,
    channelId: String,
    mediaUrl: (String) -> String,
    hazeState: HazeState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPins: () -> Unit,
    onAppearance: () -> Unit,
    onCall: (Boolean) -> Unit,
    onInfo: () -> Unit,
) {
    val frostBg = MaterialTheme.colorScheme.surface
    val frostTint = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        Modifier.fillMaxWidth().hazeEffect(hazeState) {
            backgroundColor = frostBg
            tints = listOf(HazeTint(frostTint.copy(alpha = 0.55f)))
            blurRadius = 24.dp
        },
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            }
            Avatar(
                name = title,
                model = avatarKey?.let(mediaUrl),
                size = 32.dp,
                sharedKey = "chat-avatar-$channelId",
            )
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.s),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onCall(false) }) { Icon(Icons.Outlined.Call, "voice call", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
            IconButton(onClick = { onCall(true) }) { Icon(Icons.Outlined.Videocam, "video call", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
            IconButton(onClick = onAppearance) {
                Icon(Icons.Outlined.Palette, "chat wallpaper & theme", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onPins) {
                Icon(Icons.Outlined.PushPin, "pinned messages", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, "search messages", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onInfo) {
                Icon(Icons.Outlined.Info, "conversation info", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val glass = app.pigeonsms.design.theme.LocalLiquidGlass.current
    val row: @Composable RowScope.() -> Unit = {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
        Text(
            message,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.s),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, "retry", tint = MaterialTheme.colorScheme.onErrorContainer)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, "dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (glass) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s)) {
            Row(
                Modifier.fillMaxWidth()
                    .glassPanel(Corners.card, MaterialTheme.colorScheme.errorContainer)
                    .defaultMinSize(minHeight = 48.dp).padding(start = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                content = row,
            )
        }
    } else {
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(start = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                content = row,
            )
        }
    }
}

@Composable
private fun SuperPinBanner(message: app.pigeonsms.network.MessageDto, onDismiss: () -> Unit, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = Color.Transparent,
        shape = Corners.chip,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .glassCard(Corners.chip, tint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f), accented = true),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PushPin, "Super Pin", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.s)) {
                Text("Super Pin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(message.content.ifBlank { message.attachment?.name ?: "Pinned attachment" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "hide Super Pin", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            "no messages yet",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DaySeparator(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    reply: MessageEntity?,
    self: Boolean,
    grouped: Boolean,
    isAdmin: Boolean,
    busy: Boolean,
    pinned: Boolean,
    highlighted: Boolean,
    mediaUrl: (String) -> String,
    onOpenProfile: (String) -> Unit,
    onNavigateToReply: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String, Boolean) -> Unit,
    onPin: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenMedia: (String) -> Unit,
    onSuperPin: (Boolean) -> Unit,
    onVote: (String?) -> Unit = {},
    superPinned: Boolean = false,
    seen: Boolean = false,
    groupedBelow: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var reactionPickerOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var bubbleWindowY by remember(message.id) { mutableStateOf(-1f) }
    val haptics = LocalHapticFeedback.current
    val onDoubleTapReact = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onReact("❤️", true)
    }
    val reactions = remember(message.reactionsJson) {
        runCatching { chatJson.decodeFromString<List<ReactionDto>>(message.reactionsJson) }
            .getOrDefault(emptyList())
    }
    val revisions = remember(message.revisionsJson) {
        message.revisionsJson?.let {
            runCatching { chatJson.decodeFromString<List<RevisionDto>>(it) }.getOrDefault(emptyList())
        }.orEmpty()
    }
    val canMutate = !message.deleted && message.state == "SENT"
    val canEdit = self && canMutate
    val canDelete = (self || isAdmin) && canMutate
    val imageKey = message.attachmentKey?.takeIf {
        !message.deleted && message.attachmentType?.startsWith("image/") == true
    }
    val wasEdited = !message.deleted && (message.editedAt != null || revisions.isNotEmpty())
    val replyId = message.replyTo
    val highlightColor by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        tween(220),
        label = "replyHighlight",
    )
    val tight = 7.dp
    val bubbleShape = if (self) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = if (grouped) tight else 18.dp,
            bottomStart = 18.dp,
            bottomEnd = if (groupedBelow) tight else 4.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = if (grouped) tight else 18.dp,
            topEnd = 18.dp,
            bottomStart = if (groupedBelow) tight else 4.dp,
            bottomEnd = 18.dp,
        )
    }

    Column(
        Modifier.fillMaxWidth()
            .padding(top = if (grouped) Spacing.xxs else Spacing.s)
            .background(highlightColor, Corners.card)
            .animateContentSize(tween(160))
            .alpha(if (busy) 0.72f else 1f)
            .onGloballyPositioned { bubbleWindowY = it.positionInWindow().y },
    ) {
        var dragX by remember(message.id) { mutableStateOf(0f) }
        var replyArmed by remember(message.id) { mutableStateOf(false) }
        val swipeOffset by animateFloatAsState(dragX, label = "swipeReply")
        Box {
        if (swipeOffset > 8f) {
            Icon(
                Icons.AutoMirrored.Outlined.Reply,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.s).size(22.dp)
                    .graphicsLayer {
                        val f = (swipeOffset / 120f).coerceIn(0f, 1f)
                        alpha = f
                        scaleX = 0.6f + 0.4f * f
                        scaleY = 0.6f + 0.4f * f
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .offset { IntOffset(swipeOffset.roundToInt(), 0) }
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (replyArmed) onReply()
                            dragX = 0f
                            replyArmed = false
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragX = (dragX + dragAmount).coerceIn(0f, 160f)
                        val armed = dragX > 120f
                        if (armed && !replyArmed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        replyArmed = armed
                    }
                },
            horizontalArrangement = if (self) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!self) {
                Box(Modifier.width(40.dp), contentAlignment = Alignment.BottomCenter) {
                    if (!grouped) {
                        Box(
                            Modifier.size(40.dp)
                                .clip(CircleShape)
                                .clickable { onOpenProfile(message.authorId) }
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "open ${message.authorName} profile"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Avatar(
                                name = message.authorName,
                                model = message.authorAvatar?.let(mediaUrl),
                                size = 32.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(Spacing.s))
            }

            Column(
                horizontalAlignment = if (self) Alignment.End else Alignment.Start,
                modifier = Modifier.fillMaxWidth(if (self) 0.84f else 0.82f),
            ) {
                if (!grouped && !self) {
                    Row(
                        Modifier.padding(start = Spacing.s, end = Spacing.s, bottom = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Text(
                            message.authorName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = authorColor(message.authorId),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            smartTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (imageKey != null) {
                    Column(
                        modifier = Modifier.clip(bubbleShape)
                            .then(
                                if (self) Modifier.background(
                                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)),
                                ) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                            .combinedClickable(
                            enabled = canMutate && !busy,
                            onClick = { menuOpen = true },
                            onLongClick = { menuOpen = true },
                            onDoubleClick = { onDoubleTapReact() },
                        ),
                        horizontalAlignment = if (self) Alignment.End else Alignment.Start,
                    ) {
                        if (replyId != null) {
                            ReplyPreview(reply, self = false) { onNavigateToReply(replyId) }
                            Spacer(Modifier.height(Spacing.s))
                        }
                        AttachmentView(
                            name = message.attachmentName,
                            type = message.attachmentType,
                            url = mediaUrl(imageKey),
                            self = self,
                            onOpenMedia = { onOpenMedia(message.id) },
                        )
                            if (message.content.isNotBlank()) {
                            MarkdownMessage(
                                message.content,
                                color = bubbleContentColor(self),
                                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.s),
                            )
                        }
                        if (wasEdited) {
                            Text(
                                "edited",
                                modifier = Modifier.padding(horizontal = Spacing.xs),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier.clip(bubbleShape)
                            .then(
                                if (self) Modifier.background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                    ),
                                )
                                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            .combinedClickable(
                                enabled = canMutate && !busy,
                                onClick = { menuOpen = true },
                                onLongClick = { menuOpen = true },
                                onDoubleClick = { onDoubleTapReact() },
                            )
                            .padding(horizontal = Spacing.m, vertical = Spacing.s),
                    ) {
                        Column {
                            if (replyId != null) {
                                ReplyPreview(reply, self) { onNavigateToReply(replyId) }
                                Spacer(Modifier.height(Spacing.s))
                            }
                            if (!message.deleted || isAdmin) {
                                message.attachmentKey?.let { key ->
                                    AttachmentView(
                                        name = message.attachmentName,
                                        type = message.attachmentType,
                                        url = mediaUrl(key),
                                        self = self,
                                        onOpenMedia = { onOpenMedia(message.id) },
                                    )
                                    if (message.content.isNotBlank()) Spacer(Modifier.height(Spacing.s))
                                }
                            }
                            when {
                                message.deleted && isAdmin -> {
                                    Text(
                                        message.content,
                                        color = bubbleContentColor(self).copy(alpha = 0.58f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        textDecoration = TextDecoration.LineThrough,
                                    )
                                    Text(
                                        "deleted",
                                        color = bubbleContentColor(self).copy(alpha = 0.64f),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                message.deleted -> Text(
                                    "message deleted",
                                    color = bubbleContentColor(self).copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                message.kind == "poll" -> PollMessageContent(
                                    pollJson = message.pollJson,
                                    enabled = canMutate && !busy,
                                    onVote = onVote,
                                    contentColor = bubbleContentColor(self),
                                )
                                message.kind == "event" -> EventMessageContent(
                                    metadataJson = message.metadataJson,
                                    contentColor = bubbleContentColor(self),
                                )
                                message.content.isNotBlank() -> if (isEmojiOnly(message.content)) Text(
                                    message.content,
                                    color = bubbleContentColor(self),
                                    style = MaterialTheme.typography.displaySmall,
                                ) else MarkdownMessage(message.content, color = bubbleContentColor(self))
                            }
                            val previewUrl = if (!message.deleted) firstUrlIn(message.content) else null
                            if (previewUrl != null) {
                                Spacer(Modifier.height(Spacing.s))
                                LinkPreviewCard(previewUrl, self)
                            }
                            if (wasEdited) {
                                Text(
                                    "edited",
                                    color = bubbleContentColor(self).copy(alpha = 0.58f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

                MessageMeta(
                    message = message,
                    showSentTime = self || (imageKey != null && grouped),
                    onRetry = onRetry,
                    seen = seen,
                )

                if (!message.deleted) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        reactions.forEach { reaction ->
                            ReactionChip(
                                reaction = reaction,
                                enabled = canMutate && !busy,
                                onClick = { onReact(reaction.emoji, !reaction.me) },
                            )
                        }
                    }
                }
            }
        }
        }
    }

    if (menuOpen) {
        MessageMenu(
            message = message,
            self = self,
            anchorY = bubbleWindowY,
            reactions = reactions,
            deleted = message.deleted,
            canReact = canMutate,
            canEdit = canEdit,
            canDelete = canDelete,
            canPin = canMutate,
            canSuperPin = isAdmin || self,
            pinned = pinned,
            superPinned = superPinned,
            onDismiss = { menuOpen = false },
            onReply = { menuOpen = false; onReply() },
            onEdit = { menuOpen = false; onEdit() },
            onDelete = { menuOpen = false; confirmDelete = true },
            onReact = { emoji, on -> menuOpen = false; onReact(emoji, on) },
            onPin = { on -> menuOpen = false; onPin(on) },
            onReactPicker = { menuOpen = false; reactionPickerOpen = true },
            onSuperPin = { on -> menuOpen = false; onSuperPin(on) },
        )
    }

    if (reactionPickerOpen) {
        ReactionPickerDialog(
            reactions = reactions,
            onDismiss = { reactionPickerOpen = false },
            onReact = { emoji, on ->
                reactionPickerOpen = false
                onReact(emoji, on)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("delete message?") },
            text = { Text("This message will be removed for everyone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("cancel") }
            },
        )
    }
}

@Composable
private fun ReplyPreview(reply: MessageEntity?, self: Boolean, onClick: () -> Unit) {
    val summary = reply?.let(::replySummary) ?: "original message unavailable"
    Row(
        Modifier.fillMaxWidth()
            .clip(Corners.chip)
            .background(bubbleContentColor(self).copy(alpha = 0.1f))
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = "go to original message: $summary" }
            .padding(Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            Modifier.width(3.dp).height(34.dp)
                .background(bubbleContentColor(self).copy(alpha = 0.72f), CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                reply?.authorName ?: "original message",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = bubbleContentColor(self).copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = bubbleContentColor(self).copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun replySummary(reply: MessageEntity): String = when {
    reply.deleted -> "message deleted"
    reply.attachmentType?.startsWith("image/") == true -> "📷 Photo"
    reply.attachmentType?.startsWith("video/") == true -> "🎥 Video"
    reply.attachmentType?.startsWith("audio/") == true -> "🎙️ Audio"
    reply.attachmentKey != null -> "📎 Attachment"
    reply.content.isNotBlank() -> reply.content
    else -> "original message unavailable"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageMeta(message: MessageEntity, showSentTime: Boolean, onRetry: () -> Unit, seen: Boolean = false) {
    when (message.state) {
        "SENDING" -> Text(
            "sending...",
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        "FAILED" -> Text(
            "failed - tap to retry",
            modifier = Modifier.combinedClickable(onClick = onRetry, onLongClick = onRetry)
                .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
        else -> if (showSentTime || seen) {
            Row(
                Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs).widthIn(max = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    smartTime(message.createdAt),
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (seen) Text("seen", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReactionChip(reaction: ReactionDto, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (reaction.me) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        tween(180),
        label = "reactionContainer",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        border = if (reaction.me) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else null,
        interactionSource = interactionSource,
        modifier = Modifier.pressScale(interactionSource, pressedScale = 0.92f)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = "${reaction.emoji} reaction, ${reaction.count}"
                role = Role.Button
            },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(reaction.emoji, style = MaterialTheme.typography.bodyMedium)
            Text(
                reaction.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (reaction.me) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReactionPickerDialog(
    reactions: List<ReactionDto>,
    onDismiss: () -> Unit,
    onReact: (String, Boolean) -> Unit,
) {
    var customEmoji by rememberSaveable { mutableStateOf("") }
    val selected = remember(reactions) {
        reactions.filter(ReactionDto::me).map(ReactionDto::emoji).toSet()
    }
    val custom = customEmoji.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AddReaction, contentDescription = null) },
        title = { Text("add reaction") },
        text = {
            Column {
                Text(
                    "Choose a quick reaction or enter any emoji.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    reactionChoices.forEach { emoji ->
                        val isSelected = emoji in selected
                        val pickerSource = remember { MutableInteractionSource() }
                        val pickerColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            tween(160),
                            label = "pickerColor",
                        )
                        Surface(
                            onClick = { onReact(emoji, !isSelected) },
                            shape = CircleShape,
                            color = pickerColor,
                            interactionSource = pickerSource,
                            modifier = Modifier.pressScale(pickerSource, pressedScale = 0.9f).size(48.dp).semantics {
                                role = Role.Button
                                contentDescription = if (isSelected) {
                                    "remove $emoji reaction"
                                } else {
                                    "add $emoji reaction"
                                }
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = customEmoji,
                    onValueChange = { customEmoji = it.take(32) },
                    label = { Text("custom emoji") },
                    placeholder = { Text("✨") },
                    supportingText = { Text("Use your keyboard's emoji picker") },
                    singleLine = true,
                    shape = Corners.input,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onReact(custom, true) },
                enabled = custom.isNotEmpty(),
            ) { Text("react") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}

@Composable
private fun bubbleContentColor(self: Boolean): Color =
    if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

@Composable
private fun MessageMenu(
    message: MessageEntity,
    self: Boolean,
    anchorY: Float,
    reactions: List<ReactionDto>,
    deleted: Boolean,
    canReact: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    canPin: Boolean,
    canSuperPin: Boolean,
    pinned: Boolean,
    superPinned: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String, Boolean) -> Unit,
    onPin: (Boolean) -> Unit,
    onReactPicker: () -> Unit,
    onSuperPin: (Boolean) -> Unit,
) {
    val selected = remember(reactions) { reactions.filter(ReactionDto::me).map(ReactionDto::emoji).toSet() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val menuScale by animateFloatAsState(if (shown) 1f else 0.9f, tween(180), label = "menuScale")
        val menuFade by animateFloatAsState(if (shown) 1f else 0f, tween(150), label = "menuFade")
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f * menuFade))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        ) {
            var menuHeight by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            val edgePad = with(density) { 16.dp.toPx() }.toInt()
            val lift = with(density) { 64.dp.toPx() }.toInt()
            val maxY = (constraints.maxHeight - menuHeight - edgePad).coerceAtLeast(edgePad)
            val targetY = if (anchorY >= 0f) (anchorY.toInt() - lift).coerceIn(edgePad, maxY)
            else constraints.maxHeight / 3
            Column(
                Modifier.align(Alignment.TopStart)
                    .offset { IntOffset(0, targetY) }
                    .onGloballyPositioned { menuHeight = it.size.height }
                    .padding(horizontal = Spacing.l).fillMaxWidth().widthIn(max = 360.dp)
                    .graphicsLayer { scaleX = menuScale; scaleY = menuScale; alpha = menuFade },
                horizontalAlignment = if (self) Alignment.End else Alignment.Start,
            ) {
                if (!deleted && canReact) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Transparent,
                        shadowElevation = 8.dp,
                        modifier = Modifier.glassCard(CircleShape, tint = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            reactionChoices.forEach { emoji ->
                                val isSelected = emoji in selected
                                val quickBg by animateColorAsState(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    tween(160),
                                    label = "quickReactBg",
                                )
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape)
                                        .background(quickBg)
                                        .clickableScale(pressedScale = 0.88f) { onReact(emoji, !isSelected) }
                                        .semantics {
                                            contentDescription = if (isSelected) "remove $emoji reaction" else "add $emoji reaction"
                                            role = Role.Button
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.s))
                }
                Box(
                    Modifier.clip(
                        if (self) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
                        else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
                    )
                        .background(
                            if (self) Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer),
                            )
                            else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                ) {
                    Text(
                        if (message.deleted) "message deleted" else message.content.ifBlank { "attachment" },
                        color = if (self) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Spacing.s))
                Surface(
                    shape = Corners.card,
                    color = Color.Transparent,
                    shadowElevation = 8.dp,
                    modifier = Modifier.glassCard(Corners.card),
                ) {
                    Column(Modifier.width(250.dp).padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
                        if (!deleted) MenuRow(Icons.AutoMirrored.Outlined.Reply, "reply", onReply)
                        if (!deleted && canReact) MenuRow(Icons.Outlined.AddReaction, "add reaction", onReactPicker)
                        if (canPin) {
                            MenuRow(
                                icon = Icons.Outlined.PushPin,
                                label = if (pinned) "unpin" else "pin",
                                onClick = { onPin(!pinned) },
                            )
                        }
                        if (canSuperPin) {
                            MenuRow(
                                icon = Icons.Outlined.PushPin,
                                label = if (superPinned) "remove Super Pin" else "Super Pin",
                                onClick = { onSuperPin(!superPinned) },
                            )
                        }
                        if (canEdit) MenuRow(Icons.Outlined.Edit, "edit", onEdit)
                        if (canDelete) MenuRow(Icons.Outlined.Delete, "delete", onDelete, danger = true)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).combinedClickable(
            onClick = onClick,
            onLongClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.m))
        Text(label, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

private object ActiveVoice {
    private var current: android.media.MediaPlayer? = null
    private var onPreempt: (() -> Unit)? = null
    fun claim(p: android.media.MediaPlayer, preempt: () -> Unit) {
        if (current !== p) {
            runCatching { if (current?.isPlaying == true) current?.pause() }
            onPreempt?.invoke()
        }
        current = p
        onPreempt = preempt
    }
    fun forget(p: android.media.MediaPlayer) {
        if (current === p) { current = null; onPreempt = null }
    }
}

@Composable
private fun AttachmentView(name: String?, type: String?, url: String, self: Boolean, onOpenMedia: () -> Unit = {}) {
    if (type?.startsWith("image/") == true) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = name ?: "image attachment",
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(4f / 3f)
                .clip(Corners.chip)
                .background(bubbleContentColor(self).copy(alpha = 0.08f))
                .clickable { onOpenMedia() },
        )
    } else if (type?.startsWith("video/") == true) {
        EmbeddedVideo(url, name, onFullscreen = onOpenMedia)
    } else if (type?.startsWith("audio/") == true) {
        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var positionMs by remember { mutableStateOf(0) }
        var durationMs by remember { mutableStateOf(0) }
        var speed by remember { mutableStateOf(1f) }
        val player = remember { android.media.MediaPlayer() }
        val amps = remember(url) { pseudoWaveform(url) }
        val content = bubbleContentColor(self)
        DisposableEffect(url) { onDispose { ActiveVoice.forget(player); runCatching { player.release() } } }
        LaunchedEffect(playing) {
            while (playing && isActive) {
                runCatching {
                    positionMs = player.currentPosition
                    if (player.duration > 0) durationMs = player.duration
                }
                delay(50)
            }
        }
        fun toggle() {
            runCatching {
                when {
                    player.isPlaying -> { player.pause(); playing = false }
                    prepared -> {
                        ActiveVoice.claim(player) { playing = false }
                        player.start()
                        if (speed != 1f) runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
                        playing = true
                    }
                    else -> {
                        player.setDataSource(url)
                        player.setOnPreparedListener {
                            prepared = true; durationMs = it.duration
                            ActiveVoice.claim(player) { playing = false }
                            it.start()
                            if (speed != 1f) runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) }
                            playing = true
                        }
                        player.setOnCompletionListener { playing = false; positionMs = 0 }
                        player.prepareAsync()
                    }
                }
            }
        }
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Row(
            Modifier.fillMaxWidth().widthIn(max = 280.dp).defaultMinSize(minHeight = 48.dp)
                .clip(Corners.chip)
                .background(content.copy(alpha = 0.1f))
                .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { toggle() }) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "play voice message",
                    tint = content,
                )
            }
            Waveform(
                amps = amps,
                progress = progress,
                playedColor = content,
                unplayedColor = content.copy(alpha = 0.28f),
                modifier = Modifier.weight(1f)
                    .pointerInput(prepared, durationMs) {
                        detectTapGestures { off ->
                            if (prepared && durationMs > 0) {
                                val frac = (off.x / size.width).coerceIn(0f, 1f)
                                val target = (frac * durationMs).toInt()
                                runCatching { player.seekTo(target); positionMs = target }
                            }
                        }
                    },
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                formatDuration((if (playing || positionMs > 0) positionMs else durationMs).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
                modifier = Modifier.clip(CircleShape)
                    .background(content.copy(alpha = 0.14f))
                    .clickableScale(pressedScale = 0.9f) {
                        speed = if (speed >= 2f) 1f else speed + 0.5f
                        if (playing) runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
                    }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xxs),
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .clip(Corners.chip)
                .background(bubbleContentColor(self).copy(alpha = 0.1f))
                .padding(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AttachFile,
                null,
                tint = bubbleContentColor(self).copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                name ?: "attachment",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = bubbleContentColor(self),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Composer(
    ui: ChatUiState,
    isSpace: Boolean,
    onSend: (String) -> Unit,
    onTyping: () -> Unit,
    onAttachment: (ByteArray, String, String, String) -> Unit,
    onAttachmentError: (String) -> Unit,
    onClearReply: () -> Unit,
    onClearEdit: () -> Unit,
    onMentionLookup: () -> Unit = {},
    mediaUrl: (String) -> String = { it },
    onCreatePoll: () -> Unit = {},
    onCreateEvent: () -> Unit = {},
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var readingAttachment by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var pendingSentText by remember { mutableStateOf<String?>(null) }
    var keepKeyboardAfterSend by remember { mutableStateOf(false) }
    var pendingImageEdit by remember { mutableStateOf<SelectedAttachment?>(null) }
    fun dispatchAttachment(bytes: ByteArray, name: String, type: String) {
        pendingSentText = text.text
        keepKeyboardAfterSend = true
        focusRequester.requestFocus()
        keyboardController?.show()
        onAttachment(bytes, name, type, text.text)
    }
    fun consumeAttachment(uri: Uri?) {
        if (uri != null) {
            scope.launch {
                readingAttachment = true
                runCatching { readAttachment(context, uri) }
                    .onSuccess { selected ->
                        if (selected.type.startsWith("image/") &&
                            !selected.type.equals("image/gif", ignoreCase = true) &&
                            !selected.type.equals("image/svg+xml", ignoreCase = true)
                        ) {
                            pendingImageEdit = selected
                        } else {
                            dispatchAttachment(selected.bytes, selected.name, selected.type)
                        }
                    }
                    .onFailure { error ->
                        onAttachmentError(
                            if (error is AttachmentTooLargeException) "attachments can be up to 50 MB"
                            else "couldn't read attachment",
                        )
                    }
                readingAttachment = false
            }
        }
    }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> consumeAttachment(uri) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> consumeAttachment(uri) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> consumeAttachment(uri) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            scope.launch {
                readingAttachment = true
                runCatching { readAttachment(context, uri) }
                    .onSuccess { selected ->
                        pendingImageEdit = SelectedAttachment(selected.bytes, "photo.jpg", "image/jpeg")
                    }
                    .onFailure { onAttachmentError("couldn't read photo") }
                readingAttachment = false
            }
        }
    }
    fun launchCameraCapture() {
        runCatching {
            val photoFile = java.io.File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }.onFailure {
            pendingCameraUri = null
            onAttachmentError("couldn't open camera")
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCameraCapture() else onAttachmentError("camera permission is needed to take photos")
    }
    fun requestCameraCapture() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    val haptics = LocalHapticFeedback.current
    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val liveAmps = remember { mutableStateListOf<Float>() }
    val recorderRef = remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    val voiceFileRef = remember { mutableStateOf<java.io.File?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            recorderRef.value?.let { rec ->
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
            recorderRef.value = null
            voiceFileRef.value?.let { f -> runCatching { if (f.exists()) f.delete() } }
        }
    }
    fun startRecording() {
        runCatching {
            val file = java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val rec = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context)
            else @Suppress("DEPRECATION") android.media.MediaRecorder()
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioChannels(1)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorderRef.value = rec
            voiceFileRef.value = file
            liveAmps.clear()
            elapsedMs = 0L
            paused = false
            recording = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }.onFailure { onAttachmentError("couldn't start recording") }
    }
    fun finishRecording(send: Boolean) {
        runCatching { recorderRef.value?.stop() }
        runCatching { recorderRef.value?.release() }
        recorderRef.value = null
        recording = false
        paused = false
        val file = voiceFileRef.value
        if (send && file != null && file.exists() && file.length() > 0) {
            val bytes = file.readBytes()
            runCatching { file.delete() } // cache file is spent once the bytes are read
            onAttachment(bytes, "voice.m4a", "audio/mp4", "")
        } else {
            if (send) onAttachmentError("recording was too short")
            runCatching { file?.delete() }
        }
        voiceFileRef.value = null
    }
    fun togglePause() {
        val rec = recorderRef.value ?: return
        runCatching {
            if (paused) { rec.resume(); paused = false } else { rec.pause(); paused = true }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(recording, paused) {
        while (recording && !paused && isActive) {
            val amp = runCatching { recorderRef.value?.maxAmplitude ?: 0 }.getOrDefault(0)
            val norm = (amp / 14_000f).coerceIn(0.06f, 1f)
            if (liveAmps.size >= 42) liveAmps.removeAt(0)
            liveAmps.add(norm)
            elapsedMs += 90
            delay(90)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }

    LaunchedEffect(ui.editing?.id) {
        ui.editing?.let {
            text = TextFieldValue(it.content, TextRange(it.content.length))
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(ui.composerClearToken) {
        if (ui.composerClearToken > 0) {
            val submitted = pendingSentText
            if (submitted == null || text.text == submitted) text = TextFieldValue("")
            pendingSentText = null
            if (keepKeyboardAfterSend) {
                focusRequester.requestFocus()
                delay(50)
                keyboardController?.show()
                keepKeyboardAfterSend = false
            }
        }
    }
    LaunchedEffect(ui.sending) {
        if (keepKeyboardAfterSend) {
            focusRequester.requestFocus()
            delay(50)
            keyboardController?.show()
        }
    }

    val mentionToken = remember(text) { activeMentionToken(text) }
    LaunchedEffect(mentionToken != null) { if (mentionToken != null) onMentionLookup() }
    val mentionSuggestions = remember(mentionToken, ui.mentionCandidates, ui.canMentionEveryone, isSpace) {
        val query = mentionToken?.second?.lowercase() ?: return@remember emptyList<MentionCandidate>()
        buildList {
            if (isSpace && ui.canMentionEveryone && "everyone".startsWith(query)) {
                add(MentionCandidate(id = "@everyone", username = "everyone", displayName = "notify everyone in this nest"))
            }
            addAll(
                ui.mentionCandidates
                    .filter {
                        it.username.lowercase().startsWith(query) ||
                            it.displayName?.lowercase()?.startsWith(query) == true
                    }
                    .sortedWith(
                        compareByDescending<MentionCandidate> { it.username.lowercase().startsWith(query) }
                            .thenBy { it.username.lowercase() },
                    ),
            )
        }.take(25)
    }
    fun insertMention(username: String) {
        val token = activeMentionToken(text) ?: return
        val replacement = "@$username "
        val start = token.first
        val end = start + 1 + token.second.length // "@" + partial word
        val newText = text.text.replaceRange(start, end, replacement)
        text = TextFieldValue(newText, TextRange(start + replacement.length))
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(tween(160))
                .padding(horizontal = Spacing.s, vertical = Spacing.s),
        ) {
            if (mentionSuggestions.isNotEmpty() && !recording) {
                MentionSuggestions(
                    suggestions = mentionSuggestions,
                    mediaUrl = mediaUrl,
                    onPick = ::insertMention,
                )
            }
            AnimatedVisibility(
                visible = ui.replyTo != null,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(100)),
            ) {
                ComposerContextRow(
                    title = "replying to ${ui.replyTo?.authorName.orEmpty()}",
                    detail = ui.replyTo?.let(::replySummary).orEmpty(),
                    onClose = onClearReply,
                )
            }
            AnimatedVisibility(
                visible = ui.editing != null,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(100)),
            ) {
                ComposerContextRow(
                    title = "editing message",
                    detail = ui.editing?.content.orEmpty(),
                    onClose = {
                        onClearEdit()
                        text = TextFieldValue("")
                    },
                )
            }
            if (recording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                ) {
                    IconButton(onClick = { finishRecording(send = false) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Delete, "discard recording", tint = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        Modifier.weight(1f).clip(Corners.input)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error))
                        Spacer(Modifier.width(Spacing.s))
                        Text(formatDuration(elapsedMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(Spacing.s))
                        Waveform(
                            amps = liveAmps,
                            progress = 1f,
                            playedColor = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            unplayedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    IconButton(onClick = { togglePause() }, modifier = Modifier.size(48.dp)) {
                        Icon(if (paused) Icons.Outlined.Mic else Icons.Outlined.Pause, if (paused) "resume" else "pause", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    FilledIconButton(
                        onClick = { finishRecording(send = true) },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Icon(Icons.Outlined.Send, "send voice message") }
                }
            } else Row(verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = { showAttachmentOptions = true },
                    enabled = !readingAttachment && !ui.sending && ui.editing == null,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (readingAttachment || ui.sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Outlined.AttachFile,
                            "attach file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        val capped = value.text.take(4_000)
                        text = if (capped.length == value.text.length) value else TextFieldValue(capped, TextRange(capped.length))
                        onTyping()
                    },
                    placeholder = { Text("message") },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    shape = Corners.input,
                    minLines = 1,
                    maxLines = 6,
                    enabled = !readingAttachment,
                    keyboardOptions = KeyboardOptions.Default,
                    keyboardActions = KeyboardActions.Default,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
                Spacer(Modifier.width(Spacing.xs))
                when {
                    text.text.isBlank() && ui.editing == null -> FilledIconButton(
                        onClick = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                startRecording()
                            } else {
                                micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !ui.sending && !readingAttachment,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Icon(Icons.Outlined.Mic, "record voice message") }
                    else -> FilledIconButton(
                        onClick = {
                            val submitted = text.text
                            pendingSentText = submitted
                            keepKeyboardAfterSend = true
                            focusRequester.requestFocus()
                            keyboardController?.show()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSend(submitted)
                        },
                        enabled = text.text.isNotBlank() && !ui.sending && !readingAttachment,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            if (ui.editing != null) Icons.Outlined.Done else Icons.Outlined.Send,
                            if (ui.editing != null) "save edit" else "send message",
                        )
                    }
                }
            }
        }
    }
    pendingImageEdit?.let { selected ->
        ImageEditorDialog(
            originalBytes = selected.bytes,
            filename = selected.name,
            type = selected.type,
            onDismiss = { pendingImageEdit = null },
            onSend = { edited ->
                pendingImageEdit = null
                dispatchAttachment(edited.bytes, edited.filename, edited.type)
            },
        )
    }
    if (showAttachmentOptions) {
        AttachmentOptionsSheet(
            isSpace = isSpace,
            onAction = { action ->
                showAttachmentOptions = false
                when (action) {
                    AttachmentAction.PhotosVideos -> photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    AttachmentAction.Camera -> requestCameraCapture()
                    AttachmentAction.Documents -> attachmentLauncher.launch("*/*")
                    AttachmentAction.Audio -> audioLauncher.launch("audio/*")
                    AttachmentAction.Poll -> if (isSpace) onCreatePoll() else onAttachmentError("polls are available in bird nests")
                    AttachmentAction.Event -> if (isSpace) onCreateEvent() else onAttachmentError("events are available in bird nests")
                }
            },
            onDismiss = { showAttachmentOptions = false },
        )
    }
}

private fun activeMentionToken(value: TextFieldValue): Pair<Int, String>? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.end
    val s = value.text
    if (cursor > s.length) return null
    var i = cursor - 1
    while (i >= 0 && isMentionWordChar(s[i])) i--
    if (i < 0 || s[i] != '@') return null
    if (i > 0 && isMentionWordChar(s[i - 1])) return null
    return i to s.substring(i + 1, cursor)
}

private fun isMentionWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

@Composable
private fun MentionSuggestions(
    suggestions: List<MentionCandidate>,
    mediaUrl: (String) -> String,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs)
            .heightIn(max = 48.dp * 5)
            .glassPanel(Corners.card, MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(vertical = Spacing.xs),
    ) {
        items(suggestions, key = { it.id }) { candidate ->
            val everyone = candidate.id == "@everyone"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(candidate.username) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (everyone) {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "@",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    Avatar(
                        name = candidate.username,
                        model = candidate.avatarKey?.let(mediaUrl),
                        size = 32.dp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "@${candidate.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = candidate.displayName?.takeIf { it.isNotBlank() && it != candidate.username }
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerContextRow(title: String, detail: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).padding(start = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, "cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(vm: ChatViewModel) {
    val ui by vm.ui.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = vm::closeSearch,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Spacing.l)) {
            Text(
                "search messages",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = Spacing.m),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                placeholder = { Text("search") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; vm.search("") }) {
                            Icon(Icons.Outlined.Close, "clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = Corners.input,
            )
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 560.dp),
                contentPadding = PaddingValues(vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(ui.searchResults, key = { it.id }) { result ->
                    Surface(shape = Corners.chip, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    result.author.display_name ?: result.author.username,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    smartTime(result.created_at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                result.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinsSheet(vm: ChatViewModel, onJumpToMessage: (String) -> Unit) {
    val ui by vm.ui.collectAsState()
    ModalBottomSheet(
        onDismissRequest = vm::closePins,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PushPin,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    "pinned messages",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (ui.loadingPins) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!ui.loadingPins && ui.pins.isEmpty()) {
                Text(
                    "no pinned messages",
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(vertical = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(ui.pins, key = { it.id }) { message ->
                    val authorName = message.author.display_name ?: message.author.username
                    Surface(
                        onClick = { onJumpToMessage(message.id) },
                        shape = Corners.chip,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.semantics {
                            contentDescription = "go to pinned message from $authorName"
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                                ) {
                                    Text(
                                        authorName,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        smartTime(message.created_at),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    message.content.ifBlank { message.attachment?.name ?: "attachment" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { vm.unpin(message.id) },
                                enabled = message.id !in ui.busyMessageIds,
                            ) {
                                Icon(
                                    Icons.Outlined.PushPin,
                                    contentDescription = "unpin message from $authorName",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class SelectedAttachment(
    val bytes: ByteArray,
    val name: String,
    val type: String,
)

private class AttachmentTooLargeException : IllegalArgumentException()

private suspend fun readAttachment(context: Context, uri: Uri): SelectedAttachment = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "attachment"
    var declaredSize = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredSize = cursor.getLong(sizeColumn)
        }
    }
    if (declaredSize > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()

    val initialCapacity = declaredSize.takeIf { it in 1..MAX_ATTACHMENT_BYTES }
        ?.toInt()
        ?.coerceAtMost(1024 * 1024)
        ?: 64 * 1024
    val output = ByteArrayOutputStream(initialCapacity)
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
            output.write(buffer, 0, count)
        }
    } ?: throw IllegalArgumentException("attachment unavailable")

    SelectedAttachment(
        bytes = output.toByteArray(),
        name = name.take(128),
        type = resolver.getType(uri)?.take(64) ?: "application/octet-stream",
    )
}

@Composable
private fun ChatWallpaper(key: String?) {
    when {
        key != null && key.startsWith("custom:") -> {
            AsyncImage(
                model = Uri.parse(key.removePrefix("custom:")),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        }
        else -> {
            val preset = wallpaperByKey(key)
            if (preset != null) {
                Box(Modifier.fillMaxSize().background(rememberWallpaperBrush(preset.stops)))
            }
        }
    }
}

@Composable
private fun ChatAccent(key: String?, content: @Composable () -> Unit) {
    if (key == null) {
        content()
        return
    }
    val accent = accentByKey(key)
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            primary = accent.bright,
            onPrimary = accent.on,
            primaryContainer = accent.deep,
            onPrimaryContainer = accent.on,
            secondary = accent.bright,
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatAppearanceSheet(
    current: ChatAppearance,
    onWallpaper: (String?) -> Unit,
    onAccent: (String?) -> Unit,
    onMute: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onWallpaper("custom:$uri")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.xl),
        ) {
            Text(
                "wallpaper",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                PigeonWallpapers.forEach { wp ->
                    val selected = (current.wallpaper ?: "none") == wp.key
                    val swatch = if (wp.stops.isEmpty()) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        Modifier.background(Brush.linearGradient(wp.stops))
                    }
                    Box(
                        Modifier.size(56.dp).clip(Corners.card).then(swatch)
                            .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, Corners.card)
                            .clickable { onWallpaper(if (wp.key == "none") null else wp.key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (wp.key == "none") {
                            Text("none", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val customSelected = current.wallpaper?.startsWith("custom:") == true
                Box(
                    Modifier.size(56.dp).clip(Corners.card).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(if (customSelected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, Corners.card)
                        .clickable {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Palette, "custom image", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                "accent",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.s),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(if (current.accent == null) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { onAccent(null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PigeonAccents.forEach { a ->
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(a.bright)
                            .border(if (current.accent == a.key) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { onAccent(a.key) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("mute this chat", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = current.muted,
                    onCheckedChange = onMute,
                    thumbContent = if (current.muted) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                )
            }
            TextButton(
                onClick = { onWallpaper(null); onAccent(null) },
                modifier = Modifier.padding(top = Spacing.s),
            ) { Text("reset to default") }
        }
    }
}

@Composable
private fun ImageLightbox(url: String, name: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(Spacing.m),
            )
        }
    }
}

private val authorPalette = listOf(
    Color(0xFFFF9D76), Color(0xFFB8A7F5), Color(0xFF7FD8A4), Color(0xFF76BEFF),
    Color(0xFFFF8FB0), Color(0xFFFFC46B), Color(0xFF6FE0D2), Color(0xFFE79BFF),
)
private fun authorColor(id: String): Color =
    authorPalette[(id.hashCode() and Int.MAX_VALUE) % authorPalette.size]

private fun isEmojiOnly(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 12) return false
    return trimmed.codePoints().toArray().all { cp ->
        cp >= 0x1F000 ||
            cp == 0x200D ||
            cp in 0x2600..0x27BF ||
            cp in 0xFE00..0xFE0F ||
            Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
    }
}

private val urlRegex = Regex("""https?://[^\s]+""")
private fun firstUrlIn(text: String): String? =
    urlRegex.find(text)?.value?.trimEnd('.', ',', ')', '!', '?')

@Composable
private fun LinkPreviewCard(url: String, self: Boolean) {
    val context = LocalContext.current
    val host = remember(url) { runCatching { java.net.URL(url).host }.getOrNull() ?: url }
    var title by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        title = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(20000)
                    val n = reader.read(buf)
                    val html = if (n > 0) String(buf, 0, n) else ""
                    Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(Corners.chip)
            .background(bubbleContentColor(self).copy(alpha = 0.08f))
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            .padding(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(Icons.Outlined.Link, null, tint = bubbleContentColor(self).copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title ?: host,
                style = MaterialTheme.typography.labelLarge,
                color = bubbleContentColor(self),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                host,
                style = MaterialTheme.typography.labelSmall,
                color = bubbleContentColor(self).copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
