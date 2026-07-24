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
import app.pigeonsms.ui.util.shimmerBrush
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.LocalReducedMotion
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.novaAuroraBackground
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.design.theme.rememberNovaPulse
import app.pigeonsms.design.components.NovaAnimatedCount
import app.pigeonsms.design.theme.PigeonAccents
import app.pigeonsms.design.theme.PigeonWallpapers
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.accentByKey
import app.pigeonsms.design.theme.rememberWallpaperBrush
import app.pigeonsms.design.theme.wallpaperByKey
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.HourglassEmpty
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentPaste
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
    // Feature 7: "seen by" — the message whose reader list is being shown.
    var seenByMessage by remember(channelId) { mutableStateOf<MessageEntity?>(null) }
    // Feature 8: multi-select. Non-empty set = selection mode is active.
    val selectedIds = remember(channelId) { mutableStateListOf<String>() }
    val selectionMode = selectedIds.isNotEmpty()
    val clipboardManager = LocalClipboardManager.current
    val multiSelectContext = LocalContext.current
    // "seen by" is offered for space channels; restricted to small nests
    // (<= 20 members). Count unknown (-1) → still offer it for spaces generally.
    val seenByEligible = isSpace && (ui.channelMemberCount in 1..20 || ui.channelMemberCount < 0)
    val listState = rememberLazyListState()
    val replyNavigationScope = rememberCoroutineScope()
    val messageById = remember(messages) { messages.associateBy(MessageEntity::id) }
    var highlightedMessageId by remember(channelId) { mutableStateOf<String?>(null) }
    // "seen" shows only under the newest own message the peer has read — computed
    // once per list change, not per rendered row
    val lastOwnId = remember(messages) {
        messages.lastOrNull { vm.isOwn(it) && !it.deleted && it.state == "SENT" }?.id
    }
    var positioned by remember(channelId) { mutableStateOf(false) }
    var lastReadSeq by remember(channelId) { mutableStateOf(0L) }
    val currentOnActive by rememberUpdatedState(onActive)
    val imeVisible = WindowInsets.isImeVisible
    // honor the "read receipts" privacy toggle — when off, never tell the server we read
    val appCtx = LocalContext.current.applicationContext
    val themePrefs by remember(appCtx) { (appCtx as app.pigeonsms.PigeonApp).container.themeStore.prefs }
        .collectAsState(initial = app.pigeonsms.data.ThemePrefs())
    val chatHaze = remember { HazeState() }
    // messages scroll under the frosted top bar, so pad the list past it
    // (Nova's header is taller, so its inset is a touch larger)
    val novaChrome = LocalExperimentalRedesign.current
    val barInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + (if (novaChrome) 72.dp else 64.dp)
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
            // scrolled up + someone else's message → count it for the FAB badge
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
    val novaAurora = novaChrome && appearance.wallpaper == null
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                // Nova's aurora mesh: iris + cyan blobs breathing over the Void so
                // the message canvas has real layered depth instead of a flat fill.
                // Only when no custom/preset wallpaper is chosen (a picture wins).
                if (novaAurora) Modifier.novaAuroraBackground(
                    accent = MaterialTheme.colorScheme.primary,
                    animate = true,
                ) else Modifier,
            ),
    ) {
        ChatWallpaper(appearance.wallpaper)
        Column(
            // Single combined inset: nav-bar height when the keyboard is closed,
            // keyboard height when open — never both stacked (that dead space was
            // pushing messages under the composer).
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) chatLayout@ {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().hazeSource(chatHaze)) {
            when {
                messages.isEmpty() && ui.initialLoading -> {
                    if (novaChrome) {
                        NovaChatSkeleton(Modifier.fillMaxSize().padding(top = barInset + Spacing.s))
                    } else {
                        LoadingIndicator(modifier = Modifier.size(48.dp).align(Alignment.Center))
                    }
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
                            // Feature 7: seen-by action (small space/nest channels only)
                            showSeenBy = seenByEligible && !message.deleted && message.seq > 0,
                            onSeenBy = { seenByMessage = message },
                            // Feature 8: multi-select
                            selectionMode = selectionMode,
                            selected = message.id in selectedIds,
                            onEnterSelection = {
                                if (message.id !in selectedIds) selectedIds.add(message.id)
                            },
                            onToggleSelected = {
                                if (message.id in selectedIds) selectedIds.remove(message.id)
                                else selectedIds.add(message.id)
                            },
                        )
                        }
                    }
                }
            }
            }
            Column(Modifier.align(Alignment.TopCenter)) {
                // Feature 8: when messages are selected, the top bar becomes a
                // contextual action bar (count + mass copy/delete + exit).
                if (selectionMode) {
                    SelectionActionBar(
                        count = selectedIds.size,
                        hazeState = chatHaze,
                        onClose = { selectedIds.clear() },
                        onCopy = {
                            // Copy in chronological order, formatted "<user>: <message>".
                            val ordered = messages
                                .filter { it.id in selectedIds && !it.deleted && it.content.isNotBlank() }
                                .joinToString("\n") { "${it.authorName}: ${it.content}" }
                            if (ordered.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(ordered))
                                Toast.makeText(multiSelectContext, "Copied ${selectedIds.size} messages", Toast.LENGTH_SHORT).show()
                            }
                            selectedIds.clear()
                        },
                        onDelete = {
                            // Mass-delete only the ones the delete action would accept
                            // (own messages, or any when admin; not already deleted).
                            messages.filter {
                                it.id in selectedIds && !it.deleted && it.state == "SENT" &&
                                    (vm.isOwn(it) || ui.isAdmin)
                            }.forEach { vm.delete(it) }
                            selectedIds.clear()
                        },
                    )
                } else
                ChatTopBar(title, avatarKey, channelId, vm::mediaUrl, chatHaze, onBack, onSearch = vm::openSearch, onPins = vm::loadPins, onAppearance = { showAppearance = true }, onCall = ::startCall, onInfo = { infoOpen = true })
                if (ui.pins.isNotEmpty()) {
                    SuperPinBanner(
                        pins = ui.pins,
                        onOpen = { pin ->
                            val idx = messages.indexOfFirst { it.id == pin.id }
                            if (idx >= 0) appearanceScope.launch { listState.animateScrollToItem(idx + if (ui.loadingOlder) 1 else 0) }
                        },
                        onUnpin = { pin -> vm.unpin(pin.id) },
                    )
                }
                AnimatedVisibility(
                    visible = ui.error != null,
                    enter = fadeIn(tween(140)) + slideInVertically(tween(140)) { -it },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it },
                ) {
                    ErrorBanner(message = ui.error.orEmpty(), onRetry = vm::refresh, onDismiss = vm::clearError)
                }
            }
            // NOVA: the jump-to-latest control springs in with a scale overshoot
            // instead of a bare fade, so it draws the eye when it arrives.
            val fabScaleSpec = NovaMotion.emphasized<Float>()
            androidx.compose.animation.AnimatedVisibility(
                visible = listState.canScrollForward && messages.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.m),
                enter = if (novaChrome) fadeIn() + androidx.compose.animation.scaleIn(fabScaleSpec, initialScale = 0.7f) else fadeIn(),
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
                        ) {
                            // the count users watch tick — sliding digits on Nova
                            if (novaChrome) NovaAnimatedCount(
                                unseenWhileUp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                            ) else Text("$unseenWhileUp")
                        }
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
            // ttl/sendAt/encrypted pass straight through to ChatViewModel.send (A8):
            // send(text, ttl, sendAt, encrypted). Edits ignore them (see the VM).
            onSend = { body, ttl, sendAt, encrypted -> vm.send(body, ttl, sendAt, encrypted) },
            onTyping = vm::typing,
            onAttachment = vm::sendAttachment,
            onAttachmentError = vm::reportError,
            onClearReply = { vm.setReply(null) },
            onClearEdit = { vm.setEditing(null) },
            onMentionLookup = vm::loadMentionCandidates,
            mediaUrl = vm::mediaUrl,
            onCreatePoll = { showCreatePoll = true },
            onCreateEvent = { showCreateEvent = true },
            // E2EE (experimental): the composer's encrypt affordance is offered only for
            // DMs (never spaces/forums) and only when the user's `e2ee` pref is on. The
            // repository still verifies keys are established before actually encrypting.
            e2eeEnabled = themePrefs.e2ee && !isSpace,
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
    if (ui.searchOpen) {
        SearchSheet(vm) { messageId ->
            // jump only works for messages already in loaded history — same
            // limitation as reply/pin navigation
            val targetIndex = messages.indexOfFirst { it.id == messageId }
            if (targetIndex < 0) {
                vm.reportError("message isn't available in loaded history")
            } else {
                vm.closeSearch()
                replyNavigationScope.launch {
                    listState.animateScrollToItem(targetIndex + if (ui.loadingOlder) 1 else 0)
                    highlightedMessageId = messageId
                    delay(1_600)
                    if (highlightedMessageId == messageId) highlightedMessageId = null
                }
            }
        }
    }
    if (ui.pinsOpen) {
        PinsSheet(vm) { messageId ->
            // jump only works for messages already in loaded history — same
            // limitation as reply navigation
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
            avatarKey = avatarKey,
            onDismiss = { infoOpen = false },
            onJumpToMessage = { messageId ->
                infoOpen = false
                // jump only works for messages already in loaded history — same
                // limitation as reply navigation
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
    seenByMessage?.let { msg ->
        SeenByDialog(
            readers = vm.seenBy(msg),
            memberCount = ui.channelMemberCount,
            mediaUrl = vm::mediaUrl,
            onOpenProfile = { id -> seenByMessage = null; onOpenProfile(id) },
            onDismiss = { seenByMessage = null },
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
    // frosted glass over the scrolling messages (they pass underneath)
    val frostBg = MaterialTheme.colorScheme.surface
    val frostTint = MaterialTheme.colorScheme.surfaceContainerHigh
    if (LocalExperimentalRedesign.current) {
        NovaChatTopBar(title, avatarKey, channelId, mediaUrl, hazeState, frostBg, frostTint, onBack, onSearch, onPins, onAppearance, onCall, onInfo)
        return
    }
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
            // tap the avatar + title to open conversation info (subtle ripple)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        onClickLabel = "open conversation info",
                        onClick = onInfo,
                    )
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            }
            // call control: single icon opening a voice/video dropdown
            Box {
                var callMenuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { callMenuOpen = true }) {
                    Icon(Icons.Outlined.Call, "call", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                DropdownMenu(expanded = callMenuOpen, onDismissRequest = { callMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Voice call") },
                        leadingIcon = { Icon(Icons.Outlined.Call, null) },
                        onClick = { callMenuOpen = false; onCall(false) },
                    )
                    DropdownMenuItem(
                        text = { Text("Video call") },
                        leadingIcon = { Icon(Icons.Outlined.Videocam, null) },
                        onClick = { callMenuOpen = false; onCall(true) },
                    )
                }
            }
            // overflow: search, conversation info, theme, pins
            Box {
                var overflowOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, "more actions", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Search") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        onClick = { overflowOpen = false; onSearch() },
                    )
                    DropdownMenuItem(
                        text = { Text("Conversation info") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = { overflowOpen = false; onInfo() },
                    )
                    DropdownMenuItem(
                        text = { Text("Theme") },
                        leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                        onClick = { overflowOpen = false; onAppearance() },
                    )
                    DropdownMenuItem(
                        text = { Text("Pins") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = { overflowOpen = false; onPins() },
                    )
                }
            }
        }
    }
}

/**
 * NOVA header — a taller, more expressive chat header. The avatar is enlarged and
 * carries a soft iris ring; the title and a "presence" subline are stacked; the
 * call + overflow actions live inside a rounded pill action-cluster so they read
 * as one control rather than loose icon buttons. Every callback is the same as the
 * classic bar (tap avatar/title → info, call dropdown, overflow menu).
 */
@Composable
private fun NovaChatTopBar(
    title: String,
    avatarKey: String?,
    channelId: String,
    mediaUrl: (String) -> String,
    hazeState: HazeState,
    frostBg: Color,
    frostTint: Color,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPins: () -> Unit,
    onAppearance: () -> Unit,
    onCall: (Boolean) -> Unit,
    onInfo: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().hazeEffect(hazeState) {
            backgroundColor = frostBg
            tints = listOf(HazeTint(frostTint.copy(alpha = 0.62f)))
            blurRadius = 30.dp
        },
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // pill-framed back affordance
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                    .clickable(onClickLabel = "back", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Spacing.s))
            // enlarged avatar with an iris ring + stacked name / presence subline
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(NovaCorners.chip)
                    .clickable(onClickLabel = "open conversation info", onClick = onInfo)
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // iris→cyan ring with a slow breathing cyan halo behind it so the
                // avatar quietly lives instead of sitting flat on the frosted bar.
                val ringPulse = rememberNovaPulse(periodMillis = 3200)
                Box(
                    Modifier.size(46.dp)
                        .novaHalo(MaterialTheme.colorScheme.secondary, alpha = 0.10f + 0.12f * ringPulse)
                        .background(
                            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)),
                            CircleShape,
                        )
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(
                            name = title,
                            model = avatarKey?.let(mediaUrl),
                            size = 40.dp,
                            sharedKey = "chat-avatar-$channelId",
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = Spacing.m)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // cyan meta cue — the dual-accent pair, and a real affordance
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                        Text(
                            "tap for info",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // action cluster: call + overflow inside one rounded pill
            Row(
                Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                    .padding(horizontal = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    var callMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { callMenuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Call, "call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = callMenuOpen, onDismissRequest = { callMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Voice call") },
                            leadingIcon = { Icon(Icons.Outlined.Call, null) },
                            onClick = { callMenuOpen = false; onCall(false) },
                        )
                        DropdownMenuItem(
                            text = { Text("Video call") },
                            leadingIcon = { Icon(Icons.Outlined.Videocam, null) },
                            onClick = { callMenuOpen = false; onCall(true) },
                        )
                    }
                }
                Box {
                    var overflowOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.MoreVert, "more actions", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Search") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            onClick = { overflowOpen = false; onSearch() },
                        )
                        DropdownMenuItem(
                            text = { Text("Conversation info") },
                            leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { overflowOpen = false; onInfo() },
                        )
                        DropdownMenuItem(
                            text = { Text("Theme") },
                            leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                            onClick = { overflowOpen = false; onAppearance() },
                        )
                        DropdownMenuItem(
                            text = { Text("Pins") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { overflowOpen = false; onPins() },
                        )
                    }
                }
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
    if (LocalExperimentalRedesign.current) {
        // NOVA: a floating error card that matches the lit-rim material system —
        // a soft drop shadow + accented rim rather than a full-bleed error bar.
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s)) {
            Row(
                Modifier.fillMaxWidth()
                    .novaElevation(
                        NovaCorners.card,
                        tint = MaterialTheme.colorScheme.errorContainer,
                        accent = MaterialTheme.colorScheme.error,
                        accented = true,
                    )
                    .defaultMinSize(minHeight = 48.dp).padding(start = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                content = row,
            )
        }
    } else if (glass) {
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

/**
 * Pinned-messages banner. Cycles through [pins] with up/down chevrons (wrapping);
 * tapping the body jumps to the shown message; long-pressing opens a small menu to
 * delete (unpin) the one currently shown. Hidden by the caller when [pins] is empty.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuperPinBanner(
    pins: List<app.pigeonsms.network.MessageDto>,
    onOpen: (app.pigeonsms.network.MessageDto) -> Unit,
    onUnpin: (app.pigeonsms.network.MessageDto) -> Unit,
) {
    if (pins.isEmpty()) return
    var index by remember(pins.size) { mutableStateOf(0) }
    // clamp against list shrinking underneath us (e.g. after an unpin)
    val safeIndex = index.coerceIn(0, pins.lastIndex)
    val current = pins[safeIndex]
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = Color.Transparent,
        shape = Corners.chip,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .glassCard(Corners.chip, tint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f), accented = true),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PushPin, "Pinned messages", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(
                Modifier.weight(1f)
                    .padding(horizontal = Spacing.s)
                    .combinedClickable(
                        onClick = { onOpen(current) },
                        onLongClick = { menuOpen = true },
                    ),
            ) {
                Text(
                    if (pins.size > 1) "Pinned ${safeIndex + 1}/${pins.size}" else "Pinned",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(current.content.ifBlank { current.attachment?.name ?: "Pinned attachment" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menuOpen = false
                            onUnpin(current)
                        },
                    )
                }
            }
            if (pins.size > 1) {
                IconButton(
                    onClick = { index = if (safeIndex == 0) pins.lastIndex else safeIndex - 1 },
                    modifier = Modifier.size(32.dp),
                ) { Icon(Icons.Outlined.KeyboardArrowUp, "previous pinned message", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp)) }
                IconButton(
                    onClick = { index = if (safeIndex == pins.lastIndex) 0 else safeIndex + 1 },
                    modifier = Modifier.size(32.dp),
                ) { Icon(Icons.Outlined.KeyboardArrowDown, "next pinned message", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    if (LocalExperimentalRedesign.current) {
        // NOVA: an accent-washed glyph disc with a slow breathing halo so an empty
        // thread reads as an intentional, inviting canvas — not a broken screen.
        val pulse = rememberNovaPulse(periodMillis = 3400)
        val accent = MaterialTheme.colorScheme.primary
        Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp)
                    .novaHalo(accent, alpha = 0.10f + 0.14f * pulse)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.20f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = NovaDepth.rimTop), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    null,
                    modifier = Modifier.size(38.dp),
                    tint = accent,
                )
            }
            Spacer(Modifier.height(Spacing.l))
            Text(
                "say hello",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "no messages yet — send the first one",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
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

/**
 * NOVA first-load skeleton — shimmer bubbles laid out like the real thread
 * (alternating sides, varied widths) so opening a chat resolves gracefully into
 * content instead of a lone spinner. Nova-only; classic keeps the spinner.
 */
@Composable
private fun NovaChatSkeleton(modifier: Modifier = Modifier) {
    // width fractions + side, hand-tuned to feel like an organic conversation
    val rows = remember {
        listOf(
            0.55f to false, 0.42f to false, 0.60f to true,
            0.48f to true, 0.66f to false, 0.38f to false,
            0.52f to true, 0.58f to false,
        )
    }
    Column(
        modifier.padding(horizontal = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        rows.forEach { (frac, self) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (self) Arrangement.End else Arrangement.Start) {
                if (!self) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(shimmerBrush()))
                    Spacer(Modifier.width(Spacing.s))
                }
                Box(
                    Modifier.fillMaxWidth(frac).height(44.dp)
                        .clip(NovaCorners.bubble)
                        .background(shimmerBrush()),
                )
            }
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    if (LocalExperimentalRedesign.current) {
        // Nova: a single centered floating pill, no dividers
        Box(Modifier.fillMaxWidth().padding(vertical = Spacing.m), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .border(1.dp, Color.White.copy(alpha = NovaDepth.rimTop), CircleShape)
                    .padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        return
    }
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
    showSeenBy: Boolean = false,
    onSeenBy: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onEnterSelection: () -> Unit = {},
    onToggleSelected: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var reactionPickerOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var bubbleWindowY by remember(message.id) { mutableStateOf(-1f) }
    val haptics = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotion.current
    val clipboard = LocalClipboardManager.current
    val copyContext = LocalContext.current
    val onDoubleTapReact = {
        if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
    val canCopy = !message.deleted && message.content.isNotBlank()
    val imageKey = message.attachmentKey?.takeIf {
        !message.deleted && message.attachmentType?.startsWith("image/") == true
    }
    val wasEdited = !message.deleted && (message.editedAt != null || revisions.isNotEmpty())
    // Capture the nullable relation once so callbacks don't rely on a smart cast
    // across a composable lambda (MessageEntity lives in a separate module).
    val replyId = message.replyTo
    val highlightColor by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        tween(220),
        label = "replyHighlight",
    )
    val nova = LocalExperimentalRedesign.current
    // corners tighten between messages of the same run; the tail only shows on the last one
    val tight = if (nova) 9.dp else 7.dp
    val bubbleShape = if (nova) {
        // NOVA bubbles are boldly asymmetric: a large soft body with one sharp
        // "beak" corner on the sending side (bottom-end for self, bottom-start for
        // others) that only appears on the last message of a run. Grouped messages
        // in the middle of a run tighten toward the sender so the run reads as a
        // single column of connected tiles.
        val big = 24.dp
        val beak = 6.dp
        if (self) {
            RoundedCornerShape(
                topStart = big,
                topEnd = if (grouped) tight else big,
                bottomStart = big,
                bottomEnd = if (groupedBelow) tight else beak,
            )
        } else {
            RoundedCornerShape(
                topStart = if (grouped) tight else big,
                topEnd = big,
                bottomStart = if (groupedBelow) tight else beak,
                bottomEnd = big,
            )
        }
    } else if (self) {
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

    // Feature 8: selected rows get an accent wash; in selection mode the whole
    // row toggles selection and the per-message gestures/menu are suppressed.
    val selectionTint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        tween(160),
        label = "selectionTint",
    )
    Column(
        Modifier.fillMaxWidth()
            .padding(top = if (grouped) Spacing.xxs else Spacing.s)
            .background(highlightColor, Corners.card)
            .then(if (selectionMode) Modifier.background(selectionTint, Corners.card) else Modifier)
            .then(
                if (selectionMode) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleSelected,
                ) else Modifier,
            )
            .animateContentSize(tween(160))
            .alpha(if (busy) 0.72f else 1f)
            .onGloballyPositioned { bubbleWindowY = it.positionInWindow().y },
    ) {
        var dragX by remember(message.id) { mutableStateOf(0f) }
        var replyArmed by remember(message.id) { mutableStateOf(false) }
        val swipeOffset by animateFloatAsState(dragX, label = "swipeReply")
        Box {
        // reply glyph revealed behind the bubble as it's dragged; pops at the arm threshold
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
                // swipe-to-reply is disabled while selecting so drags don't fight taps
                .then(
                    if (selectionMode) Modifier else Modifier.pointerInput(message.id) {
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
                        // one tick the moment the swipe arms, not on release
                        if (armed && !replyArmed && !reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        replyArmed = armed
                    }
                },
                ),
            horizontalArrangement = if (self) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Feature 8: leading check when selecting (own rows keep it on the
            // sending side by virtue of the End arrangement pushing content right).
            if (selectionMode) {
                Box(
                    Modifier.padding(bottom = Spacing.xs).size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .border(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(
                        Icons.Outlined.Check,
                        "selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.s))
            }
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
                    if (nova) {
                        // NOVA: author name sits in a small tinted capsule with the
                        // time trailing it — a distinct "chip header" over the tile.
                        Row(
                            Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            Text(
                                message.authorName,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(NovaCorners.chip)
                                    .background(authorColor(message.authorId).copy(alpha = 0.16f))
                                    .padding(horizontal = Spacing.s, vertical = Spacing.xxs),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
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
                    } else {
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
                }

                if (imageKey != null) {
                    // The whole media tile is clipped to the bubble shape. A reply
                    // banner above the media is capped to the media's own max width
                    // (320.dp, matching AttachmentView) and padded off the edges so
                    // it sits INSIDE the clip, directly above the media, and can never
                    // bleed full-width over the media or neighbouring bubbles.
                    Column(
                        modifier = Modifier.clip(bubbleShape)
                            .then(selfBubbleFill(self, nova, bubbleShape))
                            .combinedClickable(
                            enabled = (canMutate && !busy) || selectionMode,
                            onClick = { if (selectionMode) onToggleSelected() else menuOpen = true },
                            onLongClick = { if (selectionMode) onToggleSelected() else onEnterSelection() },
                            onDoubleClick = { if (!selectionMode) onDoubleTapReact() },
                        ),
                        horizontalAlignment = if (self) Alignment.End else Alignment.Start,
                    ) {
                        if (replyId != null) {
                            Box(
                                Modifier.widthIn(max = 320.dp)
                                    .padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.xs),
                            ) {
                                ReplyPreview(reply, self) { onNavigateToReply(replyId) }
                            }
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
                            .then(selfBubbleFill(self, nova, bubbleShape))
                            .combinedClickable(
                                enabled = (canMutate && !busy) || selectionMode,
                                onClick = { if (selectionMode) onToggleSelected() else menuOpen = true },
                                onLongClick = { if (selectionMode) onToggleSelected() else onEnterSelection() },
                                onDoubleClick = { if (!selectionMode) onDoubleTapReact() },
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

                // encrypted-lock + disappearing-countdown indicators, aligned to the
                // bubble's sending side. MessageEntity doesn't yet surface encrypted/
                // expiresAt (E2EE is flag-off and disappearing TTL is enforced
                // server-side for 2.8.0), so this degrades to a no-op until those
                // fields land on the cached message. The row self-guards on defaults.
                MessageSecurityRow(
                    encrypted = false,
                    expiresAt = null,
                    self = self,
                )

                MessageMeta(
                    message = message,
                    showSentTime = self || (imageKey != null && grouped),
                    onRetry = onRetry,
                    seen = seen,
                )

                if (!message.deleted) {
                    // NOVA tucks the reaction cluster up onto the bubble's lower edge
                    // (overlapping) so it reads as attached to the tile, rather than a
                    // separate row floating below it.
                    Row(
                        Modifier.horizontalScroll(rememberScrollState())
                            .then(if (nova) Modifier.offset(y = (-8).dp).animateContentSize() else Modifier.padding(top = Spacing.xs)),
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
            canCopy = canCopy,
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
            onCopy = {
                menuOpen = false
                clipboard.setText(AnnotatedString(message.content))
                Toast.makeText(copyContext, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            showSeenBy = showSeenBy,
            onSeenBy = { menuOpen = false; onSeenBy() },
            onSelect = { menuOpen = false; onEnterSelection() },
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
            val nova = LocalExperimentalRedesign.current
            Row(
                Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs).widthIn(max = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    smartTime(message.createdAt),
                    // yields space to "seen" on narrow widths instead of overlapping it
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (seen) {
                    if (nova) {
                        // NOVA: a small filled "double-tick" dot + label chip
                        Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                        Text("seen", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    } else {
                        Text("seen", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Small indicator strip under a bubble carrying two message-level security cues:
 *  - a lock glyph + "encrypted" when the message is E2EE ([encrypted] = 1),
 *  - a live disappearing countdown ("vanishes in 6h") when [expiresAt] is set.
 * Aligned to the bubble's sending side; renders nothing when neither applies.
 */
@Composable
private fun MessageSecurityRow(encrypted: Boolean, expiresAt: Long?, self: Boolean) {
    if (!encrypted && expiresAt == null) return
    // re-tick roughly once a minute so the remaining-time label stays fresh while
    // the bubble is on screen (cheap: one coroutine per visible disappearing bubble)
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    if (expiresAt != null) {
        LaunchedEffect(expiresAt) {
            while (isActive) {
                nowMs = System.currentTimeMillis()
                if (nowMs >= expiresAt) break
                delay(30_000)
            }
        }
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xxs),
        horizontalArrangement = if (self) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (encrypted) {
            Icon(Icons.Outlined.Lock, "encrypted", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(Spacing.xxs))
            Text("encrypted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        if (encrypted && expiresAt != null) Spacer(Modifier.width(Spacing.s))
        if (expiresAt != null) {
            Icon(Icons.Outlined.Timer, "disappearing message", tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(Spacing.xxs))
            Text(disappearingLabel(expiresAt, nowMs), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

/** "vanishes in 6h" / "5m" / "expired" — coarse remaining-time label for a TTL. */
private fun disappearingLabel(expiresAt: Long, nowMs: Long): String {
    val remaining = expiresAt - nowMs
    if (remaining <= 0) return "expired"
    val minutes = remaining / 60_000
    return when {
        minutes < 1 -> "vanishes in <1m"
        minutes < 60 -> "vanishes in ${minutes}m"
        minutes < 60 * 24 -> "vanishes in ${minutes / 60}h"
        else -> "vanishes in ${minutes / (60 * 24)}d"
    }
}

@Composable
private fun ReactionChip(reaction: ReactionDto, enabled: Boolean, onClick: () -> Unit) {
    val nova = LocalExperimentalRedesign.current
    val interactionSource = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (reaction.me) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        tween(180),
        label = "reactionContainer",
    )
    // NOVA: a freshly-added reaction springs into existence (bouncy overshoot)
    // keyed on the emoji so re-composition doesn't replay it. Classic pops in flat.
    var appeared by remember(reaction.emoji) { mutableStateOf(!nova) }
    LaunchedEffect(reaction.emoji) { appeared = true }
    val appearSpec = NovaMotion.pop<Float>()
    val appearScale by animateFloatAsState(
        if (appeared) 1f else 0.7f,
        if (nova) appearSpec else tween(0),
        label = "reactionAppear",
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
        modifier = Modifier
            .graphicsLayer { scaleX = appearScale; scaleY = appearScale }
            .pressScale(interactionSource, pressedScale = 0.92f)
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

/**
 * Bubble fill. Classic is untouched (flat primary→primaryContainer for self, a
 * plain tonal surface for others). NOVA gives outgoing bubbles a *diagonal*
 * iris→cyan-shifted-deep gradient so they read dimensional instead of two-close-
 * violets, tops both bubbles with a faint rim-light, and hands the other side a
 * hairline rim so both bubbles belong to the same lit material system.
 */
@Composable
private fun selfBubbleFill(self: Boolean, nova: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier {
    if (!nova) {
        return if (self) Modifier.background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)),
        ) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    return if (self) {
        Modifier
            .background(
                Brush.linearGradient(NovaGradients.selfBubble(MaterialTheme.colorScheme.primary)),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = NovaDepth.highlightTop), Color.Transparent),
                ),
                shape,
            )
    } else {
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = NovaDepth.rimTop),
                        MaterialTheme.colorScheme.primary.copy(alpha = NovaDepth.rimBottom),
                    ),
                ),
                shape,
            )
    }
}

/** iMessage-style context overlay: dimmed backdrop, the bubble lifted center-stage,
 *  reactions floating above it and actions below. */
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
    canCopy: Boolean,
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
    onCopy: () -> Unit,
    showSeenBy: Boolean = false,
    onSeenBy: () -> Unit = {},
    onSelect: () -> Unit = {},
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
            // pin the menu where the bubble sits on screen, clamped so it always fits
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
                        if (canCopy) MenuRow(Icons.Outlined.ContentCopy, "copy", onCopy)
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
                        if (showSeenBy) MenuRow(Icons.Outlined.Info, "seen by", onSeenBy)
                        MenuRow(Icons.Outlined.Done, "select", onSelect)
                        if (canEdit) MenuRow(Icons.Outlined.Edit, "edit", onEdit)
                        if (canDelete) MenuRow(Icons.Outlined.Delete, "delete", onDelete, danger = true)
                    }
                }
            }
        }
    }
}

/**
 * Feature 8 contextual action bar. Replaces the chat top bar while messages are
 * selected: shows the count and offers mass copy / mass delete, plus a close
 * affordance that exits selection mode. Uses the same frosted-glass treatment as
 * the normal top bar so it reads as a mode switch, not a separate surface.
 */
@Composable
private fun SelectionActionBar(
    count: Int,
    hazeState: HazeState,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val nova = LocalExperimentalRedesign.current
    val frostBg = MaterialTheme.colorScheme.surface
    val frostTint = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        Modifier.fillMaxWidth().hazeEffect(hazeState) {
            backgroundColor = frostBg
            tints = listOf(HazeTint(frostTint.copy(alpha = 0.6f)))
            blurRadius = 24.dp
        },
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(if (nova) 72.dp else 64.dp).padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, "exit selection", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            }
            Text(
                "$count selected",
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.s),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onCopy, enabled = count > 0) {
                Icon(Icons.Outlined.ContentCopy, "copy selected", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onDelete, enabled = count > 0) {
                Icon(Icons.Outlined.Delete, "delete selected", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Feature 7 "seen by" dialog. Lists the members whose last-read seq has reached
 * this message (derived by [ChatViewModel.seenBy] from the read map + roster).
 * Shows each reader's avatar + name; tapping a reader opens their profile.
 */
@Composable
private fun SeenByDialog(
    readers: List<MentionCandidate>,
    memberCount: Int,
    mediaUrl: (String) -> String,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Info, null) },
        title = { Text(if (readers.isEmpty()) "seen by" else "seen by ${readers.size}") },
        text = {
            if (readers.isEmpty()) {
                Text(
                    if (memberCount < 0) "No read information available yet."
                    else "No one else has read this message yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    readers.forEach { reader ->
                        val display = reader.displayName?.takeIf { it.isNotBlank() } ?: reader.username
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(Corners.chip)
                                .clickable { onOpenProfile(reader.id) }
                                .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = display,
                                model = reader.avatarKey?.let(mediaUrl),
                                size = 36.dp,
                            )
                            Column(Modifier.weight(1f).padding(start = Spacing.s)) {
                                Text(
                                    display,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "@${reader.username}",
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close") } },
    )
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

/**
 * Owns the single voice-message [android.media.MediaPlayer] for the whole screen.
 *
 * The player is hoisted here (not into the voice composable) so playback survives
 * the composable being scrolled off-screen / disposed: we do NOT release on dispose.
 * Exactly one voice plays at a time — starting a different url releases the previous
 * player. Pausing retains the playback position (MediaPlayer keeps its position while
 * paused), and completion resets position to 0 but keeps the player prepared so it can
 * replay. Composables are thin views that poll this singleton to reflect live state.
 */
private object ActiveVoice {
    private var player: android.media.MediaPlayer? = null
    private var activeUrl: String? = null
    private var prepared: Boolean = false
    private var speed: Float = 1f

    fun isActive(url: String): Boolean = activeUrl == url && player != null
    fun isPrepared(url: String): Boolean = isActive(url) && prepared
    fun isPlaying(url: String): Boolean =
        isActive(url) && runCatching { player?.isPlaying == true }.getOrDefault(false)
    fun speedOf(url: String): Float = if (isActive(url)) speed else 1f
    fun positionMs(url: String): Int =
        if (isPrepared(url)) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0
    fun durationMs(url: String): Int =
        if (isPrepared(url)) runCatching { (player?.duration ?: 0).coerceAtLeast(0) }.getOrDefault(0) else 0

    /** Release whatever is currently loaded (stops any other playing voice). */
    private fun releaseCurrent() {
        val p = player
        player = null
        activeUrl = null
        prepared = false
        speed = 1f
        runCatching { p?.setOnCompletionListener(null) }
        runCatching { p?.setOnPreparedListener(null) }
        runCatching { if (p?.isPlaying == true) p.stop() }
        runCatching { p?.release() }
    }

    /** Toggle play/pause for [url]. Pausing retains position; a new url preempts the old. */
    fun toggle(url: String, desiredSpeed: Float) {
        val p = player
        if (activeUrl == url && p != null && prepared) {
            runCatching {
                if (p.isPlaying) {
                    p.pause()
                } else {
                    speed = desiredSpeed
                    p.start()
                    runCatching { p.playbackParams = p.playbackParams.setSpeed(desiredSpeed) }
                }
            }
            return
        }
        // Different (or first) voice: tear down the previous one, then prepare this url.
        releaseCurrent()
        speed = desiredSpeed
        val fresh = android.media.MediaPlayer()
        player = fresh
        activeUrl = url
        prepared = false
        runCatching {
            fresh.setDataSource(url)
            fresh.setOnPreparedListener {
                if (player === fresh) {
                    prepared = true
                    it.start()
                    runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) }
                }
            }
            fresh.setOnCompletionListener {
                if (player === fresh) runCatching { it.seekTo(0) }
            }
            fresh.prepareAsync()
        }.onFailure { releaseCurrent() }
    }

    fun seekTo(url: String, ms: Int) {
        if (isPrepared(url)) runCatching { player?.seekTo(ms) }
    }

    fun setSpeed(url: String, desiredSpeed: Float) {
        if (isActive(url)) {
            speed = desiredSpeed
            runCatching { if (player?.isPlaying == true) player?.let { it.playbackParams = it.playbackParams.setSpeed(desiredSpeed) } }
        }
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
        // The MediaPlayer is owned by [ActiveVoice], not by this composable, so
        // scrolling this bubble off-screen (disposal) does NOT stop playback. This
        // composable is a thin view: it polls the singleton for live state and drives
        // it via toggle/seek/setSpeed. `speed` is the only piece of purely-local UI
        // intent; it is mirrored back from the singleton when this url is active.
        var playing by remember(url) { mutableStateOf(ActiveVoice.isPlaying(url)) }
        var prepared by remember(url) { mutableStateOf(ActiveVoice.isPrepared(url)) }
        var positionMs by remember(url) { mutableStateOf(ActiveVoice.positionMs(url)) }
        var durationMs by remember(url) { mutableStateOf(ActiveVoice.durationMs(url)) }
        var speed by remember(url) { mutableStateOf(ActiveVoice.speedOf(url)) }
        val amps = remember(url) { pseudoWaveform(url) }
        val content = bubbleContentColor(self)
        // Poll the hoisted player so this view reflects its current state whether we
        // started it, another composable did, or it was pre-empted / completed. Runs
        // while composed; no player is released on dispose.
        LaunchedEffect(url) {
            while (isActive) {
                playing = ActiveVoice.isPlaying(url)
                prepared = ActiveVoice.isPrepared(url)
                if (prepared) {
                    positionMs = ActiveVoice.positionMs(url)
                    val d = ActiveVoice.durationMs(url)
                    if (d > 0) durationMs = d
                    speed = ActiveVoice.speedOf(url)
                } else {
                    positionMs = 0
                }
                delay(if (playing) 50 else 250)
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
            IconButton(onClick = {
                ActiveVoice.toggle(url, speed)
                // reflect immediately; the poll loop keeps it in sync afterwards
                playing = ActiveVoice.isPlaying(url)
            }) {
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
                                ActiveVoice.seekTo(url, target)
                                positionMs = target
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
                        ActiveVoice.setSpeed(url, speed)
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
    // text, ttl (seconds; null = off), sendAt (epoch ms; null = send now),
    // encrypted (E2EE flag). ttl/sendAt/encrypted map 1:1 to the send-path contract.
    onSend: (String, Long?, Long?, Boolean) -> Unit,
    onTyping: () -> Unit,
    onAttachment: (ByteArray, String, String, String) -> Unit,
    onAttachmentError: (String) -> Unit,
    onClearReply: () -> Unit,
    onClearEdit: () -> Unit,
    onMentionLookup: () -> Unit = {},
    mediaUrl: (String) -> String = { it },
    onCreatePoll: () -> Unit = {},
    onCreateEvent: () -> Unit = {},
    // gates the "encrypt" affordance — off by default (E2EE ships flag-off)
    e2eeEnabled: Boolean = false,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var readingAttachment by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    // ---- disappearing / schedule / encrypt composer options -----------------
    // ttl in seconds (null = disappearing off). Fixed presets: 1h / 1d / 7d.
    var ttlSeconds by rememberSaveable { mutableStateOf<Long?>(null) }
    // schedule-send timestamp in epoch ms (null = send immediately)
    var scheduledAt by rememberSaveable { mutableStateOf<Long?>(null) }
    // per-message E2EE flag; only ever settable when the e2ee pref is on (DMs only).
    var encryptOn by rememberSaveable { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    // E2EE defaults ON for DMs when the pref is on (owner's test build): sync encryptOn to
    // availability — on when the affordance appears, and force off when it goes away so it
    // can never linger for a space channel or after the pref is turned off. The user can
    // still toggle it off per message via the composer's encrypt button.
    LaunchedEffect(e2eeEnabled) { encryptOn = e2eeEnabled }
    // Recent device gallery items surfaced inside the attachment sheet.
    val recentMedia = remember { mutableStateListOf<RecentMediaItem>() }
    // An image currently sitting on the clipboard, if any — drives a "paste image" affordance.
    var clipboardImageUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var pendingSentText by remember { mutableStateOf<String?>(null) }
    var keepKeyboardAfterSend by remember { mutableStateOf(false) }
    // Image awaiting the pre-send editor; dismissing the editor cancels the send.
    var pendingImageEdit by remember { mutableStateOf<SelectedAttachment?>(null) }
    // caption == null → reuse the live composer text (gallery/document/video path);
    // a non-null caption comes from the pre-send image editor and overrides it.
    fun dispatchAttachment(bytes: ByteArray, name: String, type: String, caption: String? = null) {
        val body = caption ?: text.text
        pendingSentText = body
        keepKeyboardAfterSend = true
        focusRequester.requestFocus()
        keyboardController?.show()
        onAttachment(bytes, name, type, body)
    }
    fun consumeAttachment(uri: Uri?) {
        if (uri != null) {
            scope.launch {
                readingAttachment = true
                runCatching { readAttachment(context, uri) }
                    .onSuccess { selected ->
                        // Still bitmaps go through the editor; GIF/SVG (and video/audio/docs)
                        // are sent raw so animation/vector data isn't destroyed.
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
    // Inspect the clipboard for an image (a content:// item, or an image/* mime type)
    // and surface a paste affordance when one is present.
    fun refreshClipboardImage() {
        clipboardImageUri = runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = cm?.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            val desc = clip.description
            val looksImage = desc != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
            val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
            if (uri == null) return@runCatching null
            val resolvedImage = looksImage || context.contentResolver.getType(uri)?.startsWith("image/") == true
            if (resolvedImage) uri else null
        }.getOrNull()
    }
    // Read the pasted clipboard image and route it through the same pre-send editor.
    fun pasteClipboardImage() {
        val uri = clipboardImageUri ?: return
        clipboardImageUri = null
        consumeAttachment(uri)
    }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> consumeAttachment(uri) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> consumeAttachment(uri) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> consumeAttachment(uri) }
    // Quick camera capture: TakePicture writes into a cacheDir file exposed via FileProvider,
    // then the still routes through the same pre-send image editor as gallery picks.
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
    val reducedMotion = LocalReducedMotion.current
    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val liveAmps = remember { mutableStateListOf<Float>() }
    val recorderRef = remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    val voiceFileRef = remember { mutableStateOf<java.io.File?>(null) }
    // if the composer leaves (e.g. user backs out mid-recording), stop the mic and
    // release the recorder so it doesn't keep recording in the background
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
            if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
            // stop() on a <1s recording throws and leaves an empty file — tell the
            // user instead of silently dropping the tap on send
            if (send) onAttachmentError("recording was too short")
            runCatching { file?.delete() }
        }
        voiceFileRef.value = null
    }
    fun togglePause() {
        val rec = recorderRef.value ?: return
        runCatching {
            if (paused) { rec.resume(); paused = false } else { rec.pause(); paused = true }
            if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // live amplitude + timer polling while actively recording (not paused)
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

    // ---- @mention autocomplete -------------------------------------------
    // Token under the cursor ("@" + partial word), matching the backend parser:
    // '@' must be at start or after a non-[a-z0-9_.] char.
    val mentionToken = remember(text) { activeMentionToken(text) }
    // fetch candidates lazily, the first time an "@" context appears
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

    val nova = LocalExperimentalRedesign.current
    // NOVA composer floats as a raised rounded card over the canvas: a real
    // accent-tinted drop shadow + lit rim (novaElevation) instead of a flat 1dp
    // ring, and the rim lights up when the field is focused so it "wakes up".
    // Classic stays a full-bleed tonal Surface, byte-for-byte unchanged.
    var composerFocused by remember { mutableStateOf(false) }
    // a short-lived "sent" tick drives a celebratory pop on the send button
    var sendTick by remember { mutableStateOf(0) }
    val composerAccent = MaterialTheme.colorScheme.primary
    val composerContent: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(tween(160))
                .then(
                    if (nova) Modifier
                        .padding(horizontal = Spacing.m, vertical = Spacing.s)
                        .novaElevation(
                            NovaCorners.card,
                            tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                            accent = composerAccent,
                            accented = composerFocused,
                            glow = true,
                            elevation = NovaDepth.floatingElevation,
                        )
                        .padding(horizontal = Spacing.s, vertical = Spacing.s)
                    else Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
                ),
        ) {
            // suggestion panel above the input — hidden whenever there's no "@"
            // context or nothing matches (also covers select/space/cursor-move)
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
            // options row: disappearing ttl, schedule-send, encrypt. Hidden while
            // recording or editing (options apply to fresh text sends only).
            if (!recording && ui.editing == null) {
                ComposerOptionsRow(
                    ttlSeconds = ttlSeconds,
                    scheduledAt = scheduledAt,
                    encryptOn = encryptOn,
                    e2eeEnabled = e2eeEnabled,
                    onPickTtl = { ttlSeconds = it },
                    onSchedule = { showScheduleDialog = true },
                    onToggleEncrypt = { encryptOn = !encryptOn },
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
            } else Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Paste-image affordance: appears only while an image sits on the clipboard.
                if (clipboardImageUri != null && ui.editing == null) {
                    IconButton(
                        onClick = { pasteClipboardImage() },
                        enabled = !readingAttachment && !ui.sending,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ContentPaste,
                            "paste image from clipboard",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
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
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                        .onFocusChanged {
                            composerFocused = it.isFocused
                            if (it.isFocused) refreshClipboardImage()
                        },
                    shape = if (nova) NovaCorners.input else Corners.input,
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
                    else -> {
                        val armed = text.text.isNotBlank() && !ui.sending && !readingAttachment
                        val doSend = {
                            val submitted = text.text
                            pendingSentText = submitted
                            keepKeyboardAfterSend = true
                            focusRequester.requestFocus()
                            keyboardController?.show()
                            if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sendTick++
                            // editing ignores ttl/schedule/encrypt (the VM only edits text);
                            // a fresh send carries whatever options are armed on the composer.
                            onSend(submitted, ttlSeconds, scheduledAt, encryptOn)
                            // schedule is one-shot — clear it so the next message sends now.
                            // ttl + encrypt persist (they're conversation-intent, not per-send).
                            scheduledAt = null
                        }
                        val sendIcon = if (ui.editing != null) Icons.Outlined.Done else Icons.Rounded.Send
                        val sendLabel = if (ui.editing != null) "save edit" else "send message"
                        if (nova) {
                            // NOVA send: an iris→cyan gradient disc that "pops" (spring
                            // overshoot then settle) each time a message leaves, so
                            // sending feels physical rather than a silent state change.
                            val reduced = LocalReducedMotion.current
                            // a transient 1.18→1 settle, restarted on every send tick
                            val pop = remember { androidx.compose.animation.core.Animatable(1f) }
                            val popSpec = NovaMotion.pop<Float>()
                            LaunchedEffect(sendTick) {
                                if (sendTick > 0 && !reduced) {
                                    pop.snapTo(1.18f)
                                    pop.animateTo(1f, popSpec)
                                }
                            }
                            Box(
                                Modifier.size(48.dp)
                                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                                    .novaHalo(composerAccent, alpha = if (armed) NovaDepth.haloAlpha else 0f)
                                    .clip(CircleShape)
                                    .background(
                                        if (armed) Brush.linearGradient(NovaGradients.cta(composerAccent))
                                        else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    )
                                    .clickable(enabled = armed, onClickLabel = sendLabel, onClick = doSend),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    sendIcon,
                                    sendLabel,
                                    tint = if (armed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        } else FilledIconButton(
                            onClick = doSend,
                            enabled = armed,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(sendIcon, sendLabel)
                        }
                    }
                }
            }
        }
    }
    if (nova) {
        // transparent backdrop so the pill card visibly floats above the canvas
        Box(Modifier.fillMaxWidth().background(Color.Transparent)) { composerContent() }
    } else {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) { composerContent() }
    }
    pendingImageEdit?.let { selected ->
        ImageEditorDialog(
            originalBytes = selected.bytes,
            filename = selected.name,
            type = selected.type,
            onDismiss = { pendingImageEdit = null },
            onSend = { edited, caption ->
                pendingImageEdit = null
                dispatchAttachment(edited.bytes, edited.filename, edited.type, caption = caption)
            },
        )
    }
    if (showScheduleDialog) {
        ScheduleSendDialog(
            initial = scheduledAt,
            onDismiss = { showScheduleDialog = false },
            onClear = { showScheduleDialog = false; scheduledAt = null },
            onPick = { epochMs ->
                showScheduleDialog = false
                // only a future time is a real schedule; anything else clears it
                scheduledAt = epochMs.takeIf { it > System.currentTimeMillis() }
                if (epochMs <= System.currentTimeMillis()) onAttachmentError("pick a time in the future")
            },
        )
    }
    if (showAttachmentOptions) {
        // Load the device's recent gallery items the first time the sheet opens.
        LaunchedEffect(Unit) {
            if (recentMedia.isEmpty()) {
                val items = queryRecentMedia(context)
                if (items.isNotEmpty()) {
                    recentMedia.clear()
                    recentMedia.addAll(items)
                }
            }
        }
        AttachmentOptionsSheet(
            isSpace = isSpace,
            recent = recentMedia,
            onPickRecent = { uri ->
                showAttachmentOptions = false
                // images route through the pre-send editor; videos send directly —
                // consumeAttachment already applies exactly that split.
                consumeAttachment(uri)
            },
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

/**
 * "@" + partial-word under a collapsed cursor, or null when there's no active
 * mention context. Mirrors the backend token rule
 * (`(^|[^a-z0-9_.])@([a-z0-9_.]{3,20}|everyone)\b`): the "@" must sit at the
 * start of the text or after a non-username character.
 * Returns (index of '@') to (partial word typed after it, may be empty).
 */
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

/** Glass panel of mention candidates above the composer input — max ~5 rows visible. */
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

/** Fixed disappearing-message presets. null = off. */
private val TTL_PRESETS: List<Pair<String, Long?>> = listOf(
    "off" to null,
    "1h" to 3_600L,
    "1d" to 86_400L,
    "7d" to 604_800L,
)

private fun ttlChipLabel(ttlSeconds: Long?): String =
    TTL_PRESETS.firstOrNull { it.second == ttlSeconds }?.first?.takeIf { it != "off" }
        ?: "vanish"

private fun scheduleChipLabel(scheduledAt: Long?): String {
    if (scheduledAt == null) return "schedule"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = scheduledAt }
    return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(cal.time)
}

/**
 * The row of send-modifier chips sitting just above the input: disappearing-message
 * ttl (off/1h/1d/7d via a dropdown), schedule-send (opens a date/time dialog), and —
 * only when the E2EE beta flag is on — an encrypt toggle. Each chip lights up in the
 * accent when armed so the composer visibly carries state into the next send. Shared
 * chip styling matches the rest of the composer (both classic + experimental skins).
 */
@Composable
private fun ComposerOptionsRow(
    ttlSeconds: Long?,
    scheduledAt: Long?,
    encryptOn: Boolean,
    e2eeEnabled: Boolean,
    onPickTtl: (Long?) -> Unit,
    onSchedule: () -> Unit,
    onToggleEncrypt: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Spacing.xs, start = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // disappearing ttl — a chip that opens the preset dropdown
        Box {
            var ttlMenu by remember { mutableStateOf(false) }
            ComposerOptionChip(
                icon = if (ttlSeconds != null) Icons.Outlined.Timer else Icons.Outlined.HourglassEmpty,
                label = ttlChipLabel(ttlSeconds),
                active = ttlSeconds != null,
                onClick = { ttlMenu = true },
            )
            DropdownMenu(expanded = ttlMenu, onDismissRequest = { ttlMenu = false }) {
                TTL_PRESETS.forEach { (label, seconds) ->
                    DropdownMenuItem(
                        text = { Text(if (label == "off") "off" else "disappear after $label") },
                        trailingIcon = {
                            if (seconds == ttlSeconds) Icon(Icons.Outlined.Check, "selected", modifier = Modifier.size(18.dp))
                        },
                        onClick = { ttlMenu = false; onPickTtl(seconds) },
                    )
                }
            }
        }
        // schedule-send — armed chip shows the target time; tap opens the picker,
        // long-press-equivalent clear happens via the "off" item in the picker itself.
        ComposerOptionChip(
            icon = Icons.Outlined.Schedule,
            label = scheduleChipLabel(scheduledAt),
            active = scheduledAt != null,
            onClick = onSchedule,
        )
        // encrypt — only offered when the beta flag is on (E2EE ships flag-off)
        if (e2eeEnabled) {
            ComposerOptionChip(
                icon = if (encryptOn) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                label = if (encryptOn) "encrypted" else "encrypt",
                active = encryptOn,
                onClick = onToggleEncrypt,
            )
        }
    }
}

@Composable
private fun ComposerOptionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(160),
        label = "optionChipBg",
    )
    val content = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(Corners.chip)
            .background(container)
            .then(if (active) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Corners.chip) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * Schedule-send date/time picker. Uses the platform date + time dialogs (self-contained,
 * no extra M3 state plumbing) and emits a single epoch-ms target through [onPick]. [onClear]
 * turns scheduling back off. Starts from [initial] or the next full hour.
 */
@Composable
private fun ScheduleSendDialog(
    initial: Long?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val context = LocalContext.current
    // working calendar seeded to initial or the next full hour
    val cal = remember {
        java.util.Calendar.getInstance().apply {
            timeInMillis = initial ?: (System.currentTimeMillis() + 3_600_000L)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
    }
    var pickedMs by remember { mutableStateOf(cal.timeInMillis) }
    val dateFmt = remember { java.text.SimpleDateFormat("EEE, MMM d yyyy", java.util.Locale.getDefault()) }
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    fun openDate() {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = pickedMs }
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, m); c.set(java.util.Calendar.DAY_OF_MONTH, d)
                pickedMs = c.timeInMillis
            },
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }
    fun openTime() {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = pickedMs }
        android.app.TimePickerDialog(
            context,
            { _, h, min ->
                c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, min); c.set(java.util.Calendar.SECOND, 0)
                pickedMs = c.timeInMillis
            },
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true,
        ).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Schedule, null) },
        title = { Text("schedule send") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("this message will send automatically at the chosen time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = { openDate() }, shape = Corners.chip, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(dateFmt.format(pickedMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s))
                    }
                    Surface(onClick = { openTime() }, shape = Corners.chip, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(timeFmt.format(pickedMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(pickedMs) }) { Text("schedule") } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onClear) { Text("send now", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(vm: ChatViewModel, onJumpToMessage: (String) -> Unit) {
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
                    Surface(
                        onClick = { onJumpToMessage(result.id) },
                        shape = Corners.chip,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
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

/**
 * Newest-first device gallery items (images + videos) for the attachment sheet strip.
 * Queried off the main thread; failures (e.g. missing media permission) yield an empty list.
 */
private suspend fun queryRecentMedia(context: Context, limit: Int = 30): List<RecentMediaItem> =
    withContext(Dispatchers.IO) {
        runCatching {
            val collection = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE,
            )
            val mediaImage = android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            val mediaVideo = android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val selection =
                "${android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
                    "${android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            val args = arrayOf(mediaImage.toString(), mediaVideo.toString())
            val sort = "${android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            val results = ArrayList<RecentMediaItem>(limit)
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID)
                val typeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (cursor.moveToNext() && results.size < limit) {
                    val id = cursor.getLong(idCol)
                    val isVideo = cursor.getInt(typeCol) == mediaVideo
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    results.add(RecentMediaItem(uri, isVideo))
                }
            }
            results as List<RecentMediaItem>
        }.getOrDefault(emptyList())
    }

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

/** Draws the per-chat wallpaper behind the message layer (gradient preset or picked image). */
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
            // legibility scrim so bubbles read cleanly over any photo
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

/** Re-themes the chat with a per-chat accent override; bubbles follow colorScheme.primary. */
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

/** Fullscreen image viewer (tap anywhere to close). */
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

/** WhatsApp-group-style stable per-person name color, hashed from the user id. */
private val authorPalette = listOf(
    Color(0xFFFF9D76), Color(0xFFB8A7F5), Color(0xFF7FD8A4), Color(0xFF76BEFF),
    Color(0xFFFF8FB0), Color(0xFFFFC46B), Color(0xFF6FE0D2), Color(0xFFE79BFF),
)
private fun authorColor(id: String): Color =
    authorPalette[(id.hashCode() and Int.MAX_VALUE) % authorPalette.size]

/** True for short, symbol-only messages so they render oversized (jumbomoji). */
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

/** Link card under a message: best-effort OG <title>, tap to open in the browser. */
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
