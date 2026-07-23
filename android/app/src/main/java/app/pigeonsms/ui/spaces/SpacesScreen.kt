package app.pigeonsms.ui.spaces

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.components.NovaAnimatedCount
import app.pigeonsms.design.components.NovaHero
import app.pigeonsms.design.components.NovaIconBadgeButton
import app.pigeonsms.design.components.NovaSectionLabel
import app.pigeonsms.design.components.NovaTag
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaGlow
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.ui.AppViewModel
import app.pigeonsms.ui.UnreadPill
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.LiquidSegmented
import app.pigeonsms.ui.util.LoadingState
import app.pigeonsms.ui.util.ScreenHeader
import app.pigeonsms.ui.util.clickableScale
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(app: AppViewModel, onOpenChannel: (id: String, name: String, kind: String) -> Unit) {
    val vm: SpacesViewModel = pigeonVm { c, _ -> SpacesViewModel(c.socialRepository, c.api) }
    val home by app.home.collectAsState()
    val ui by vm.ui.collectAsState()
    val skin = LocalUiSkin.current

    val galaxy = skin == UiSkin.Galaxy
    val novaSkin = skin == UiSkin.Nova
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var createChannelTarget by remember { mutableStateOf<SpaceDto?>(null) }
    var deleteTarget by remember { mutableStateOf<SpaceDto?>(null) }
    var leaveTarget by remember { mutableStateOf<SpaceDto?>(null) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var iconTarget by remember { mutableStateOf<SpaceDto?>(null) }
    var renameChannelTarget by remember { mutableStateOf<Pair<SpaceDto, app.pigeonsms.network.ChannelDto>?>(null) }
    var deleteChannelTarget by remember { mutableStateOf<Pair<SpaceDto, app.pigeonsms.network.ChannelDto>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val space = iconTarget
        if (uri == null || space == null) {
            iconTarget = null
        } else {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { readIconImage(context, uri) }
                }.onSuccess { picked ->
                    vm.changeIcon(space.id, picked.bytes, picked.type) {
                        iconTarget = null
                        app.refreshSpaces()
                    }
                }.onFailure { error ->
                    iconTarget = null
                    vm.reportError(error.message ?: "couldn't read that image")
                }
            }
        }
    }
    LaunchedEffect(Unit) { app.refreshSpaces() }
    LaunchedEffect(ui.createdChannel?.id) {
        val channel = ui.createdChannel ?: return@LaunchedEffect
        app.refreshSpaces()
        createChannelTarget = null
        vm.consumeCreatedChannel()
        onOpenChannel(channel.id, channel.name, channel.kind)
    }

    Column(Modifier.fillMaxSize()) {
        if (galaxy) {
            val nestCount = home.spaces.size
            val unreadTotal = home.spaces.sumOf { s -> s.channels.sumOf { it.unread } }
            val subtitle = when {
                nestCount == 0 -> null
                unreadTotal > 0 -> "$nestCount ${if (nestCount == 1) "nest" else "nests"} · $unreadTotal unread"
                else -> "$nestCount ${if (nestCount == 1) "nest" else "nests"} · all caught up"
            }
            NovaHero(
                title = "bird nests",
                subtitle = subtitle,
                accentSubtitle = unreadTotal > 0,
                action = {
                    NovaIconBadgeButton(onClick = { vm.clearFeedback(); sheetOpen = true }) {
                        Icon(Icons.Outlined.Add, "create or join a bird nest")
                    }
                },
            )
        } else {
            ScreenHeader("bird nests") {
                IconButton(onClick = { vm.clearFeedback(); sheetOpen = true }) {
                    Icon(Icons.Outlined.Add, "create or join a bird nest", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        AnimatedVisibility(
            visible = ui.error != null && !sheetOpen && createChannelTarget == null && deleteTarget == null && leaveTarget == null && renameChannelTarget == null && deleteChannelTarget == null,
            enter = fadeIn(PigeonMotion.snappy()),
            exit = fadeOut(PigeonMotion.snappy()),
        ) {
            Text(
                ui.error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.m).padding(bottom = Spacing.s),
            )
        }
        when {
            home.spacesLoading && home.spaces.isEmpty() -> LoadingState("loading bird nests")
            home.spacesError != null && home.spaces.isEmpty() -> ErrorState(home.spacesError!!, app::refreshSpaces)
            home.spaces.isEmpty() -> Empty(
                "no bird nests yet",
                "create one or join with a code",
                icon = Icons.Outlined.Forum,
                action = if (galaxy) {
                    {
                        NovaTag(selected = true, onClick = { vm.clearFeedback(); sheetOpen = true }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                            Text("create a nest", fontWeight = FontWeight.Bold)
                        }
                    }
                } else null,
            )
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = home.spacesLoading,
                onRefresh = { app.refreshSpaces() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                itemsIndexed(home.spaces, key = { _, s -> s.id }) { index, space ->
                    val onChangeIcon: (() -> Unit)? = if (space.role == "owner" || space.role == "admin") {
                        {
                            vm.clearFeedback()
                            iconTarget = space
                            iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    } else null
                    val onInvite: () -> Unit = { vm.invite(space.id) { code -> inviteCode = code } }
                    val onAddChannel: (() -> Unit)? = if (space.role == "owner") {
                        { vm.clearFeedback(); createChannelTarget = space }
                    } else null
                    val onDelete: (() -> Unit)? = if (space.role == "owner") {
                        { vm.clearFeedback(); deleteTarget = space }
                    } else null
                    val onLeave: (() -> Unit)? = if (space.role != "owner") {
                        { vm.clearFeedback(); leaveTarget = space }
                    } else null

                    val channelMenu: ((app.pigeonsms.network.ChannelDto) -> ChannelMenuActions)? =
                        if (space.role == "owner") {
                            { channel ->
                                ChannelMenuActions(
                                    onRename = { vm.clearFeedback(); renameChannelTarget = space to channel },
                                    onDelete = { vm.clearFeedback(); deleteChannelTarget = space to channel },
                                )
                            }
                        } else null
                    val iconBusy = ui.action == SpaceAction.ChangeIcon && iconTarget?.id == space.id
                    if (galaxy) {
                        NovaSpaceCard(
                            space = space,
                            modifier = Modifier.itemAppear(index),
                            iconUrl = app.mediaUrl(space.icon_key),
                            iconBusy = iconBusy,
                            singleNest = home.spaces.size == 1,
                            onChangeIcon = onChangeIcon,
                            onInvite = onInvite,
                            onOpenChannel = onOpenChannel,
                            onAddChannel = onAddChannel,
                            onDelete = onDelete,
                            onLeave = onLeave,
                            channelMenu = channelMenu,
                        )
                    } else if (novaSkin) {
                        ExpSpaceCard(
                            space = space,
                            modifier = Modifier.itemAppear(index),
                            iconUrl = app.mediaUrl(space.icon_key),
                            iconBusy = iconBusy,
                            singleNest = home.spaces.size == 1,
                            onChangeIcon = onChangeIcon,
                            onInvite = onInvite,
                            onOpenChannel = onOpenChannel,
                            onAddChannel = onAddChannel,
                            onDelete = onDelete,
                            onLeave = onLeave,
                            channelMenu = channelMenu,
                        )
                    } else {
                        SpaceCard(
                            space = space,
                            modifier = Modifier.itemAppear(index),
                            iconUrl = app.mediaUrl(space.icon_key),
                            iconBusy = iconBusy,
                            singleNest = home.spaces.size == 1,
                            onChangeIcon = onChangeIcon,
                            onInvite = onInvite,
                            onOpenChannel = onOpenChannel,
                            onAddChannel = onAddChannel,
                            onDelete = onDelete,
                            onLeave = onLeave,
                            channelMenu = channelMenu,
                        )
                    }
                }
            }
            }
        }
    }

    inviteCode?.let { code ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { inviteCode = null },
            icon = { Icon(Icons.Outlined.PersonAdd, null) },
            title = { Text("invite code") },
            text = {
                Text(
                    code,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
                    inviteCode = null
                }) { Text("copy") }
            },
            dismissButton = { TextButton(onClick = { inviteCode = null }) { Text("close") } },
        )
    }

    if (sheetOpen) {
        var name by rememberSaveable { mutableStateOf("") }
        var code by rememberSaveable { mutableStateOf("") }
        val busy = ui.action != null
        ModalBottomSheet(
            onDismissRequest = { if (!busy) { sheetOpen = false; vm.clearFeedback() } },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.xl)) {
                Text("create a bird nest", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("A home for channels, friends, and shared conversations.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48); vm.clearFeedback() },
                    label = { Text("bird nest name") },
                    supportingText = { Text("${name.length}/48") },
                    enabled = !busy,
                    singleLine = true,
                    shape = Corners.input,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Button(
                    onClick = { vm.create(name) { app.refreshSpaces(); sheetOpen = false } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    shape = Corners.button,
                ) {
                    ActionLabel(ui.action == SpaceAction.Create, "create bird nest")
                }

                Row(Modifier.fillMaxWidth().padding(top = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Text("or", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.m))
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
                Text("join with a code", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Spacing.l))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filterNot { char -> char.isWhitespace() }.take(20); vm.clearFeedback() },
                    label = { Text("invite code") },
                    placeholder = { Text("SPC-XXXX-XXXX") },
                    enabled = !busy,
                    singleLine = true,
                    shape = Corners.input,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                OutlinedButton(
                    onClick = { vm.join(code) { app.refreshSpaces(); sheetOpen = false } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    shape = Corners.button,
                ) {
                    ActionLabel(ui.action == SpaceAction.Join, "join bird nest")
                }
                AnimatedVisibility(
                    visible = ui.error != null,
                    enter = fadeIn(PigeonMotion.snappy()),
                    exit = fadeOut(PigeonMotion.snappy()),
                ) {
                    Text(ui.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            }
        }
    }

    createChannelTarget?.let { space ->
        var channelName by rememberSaveable(space.id) { mutableStateOf("") }
        var kindIndex by rememberSaveable(space.id) { mutableStateOf(0) }
        val kinds = listOf("text", "voice", "forum")
        val kind = kinds[kindIndex.coerceIn(0, kinds.lastIndex)]
        val busy = ui.action != null
        val submit: () -> Unit = { vm.createChannel(space.id, channelName, kind) }
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    createChannelTarget = null
                    vm.clearFeedback()
                }
            },
            title = { Text("add a channel") },
            text = {
                Column {
                    Text(
                        when (kind) {
                            "voice" -> "Create a voice channel in ${space.name}."
                            "forum" -> "Create a forum in ${space.name} — threaded posts with replies."
                            else -> "Create a text channel in ${space.name}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LiquidSegmented(
                        options = kinds,
                        selected = kindIndex.coerceIn(0, kinds.lastIndex),
                        onSelect = { if (!busy) { kindIndex = it; vm.clearFeedback() } },
                        modifier = Modifier.padding(top = Spacing.m),
                        height = 40.dp,
                    )
                    OutlinedTextField(
                        value = channelName,
                        onValueChange = { channelName = it.take(32); vm.clearFeedback() },
                        label = { Text("channel name") },
                        prefix = { Text(if (kind == "voice") "🔊 " else "#") },
                        supportingText = { Text("${channelName.length}/32") },
                        enabled = !busy,
                        singleLine = true,
                        shape = Corners.input,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                    )
                    AnimatedVisibility(
                        visible = ui.error != null,
                        enter = fadeIn(PigeonMotion.snappy()),
                        exit = fadeOut(PigeonMotion.snappy()),
                    ) {
                        Text(
                            ui.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Spacing.s),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = submit, enabled = !busy) {
                    ActionLabel(ui.action == SpaceAction.CreateChannel, "create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { createChannelTarget = null; vm.clearFeedback() },
                    enabled = !busy,
                ) { Text("cancel") }
            },
        )
    }

    deleteTarget?.let { space ->
        AlertDialog(
            onDismissRequest = { if (ui.action == null) { deleteTarget = null; vm.clearFeedback() } },
            title = { Text("delete ${space.name}?") },
            text = {
                Column {
                    Text("This removes the bird nest and its channels for everyone. This cannot be undone.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.delete(space.id) { app.refreshSpaces(); deleteTarget = null } },
                    enabled = ui.action == null,
                ) { ActionLabel(ui.action == SpaceAction.Delete, "delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null; vm.clearFeedback() }, enabled = ui.action == null) { Text("cancel") }
            },
        )
    }

    leaveTarget?.let { space ->
        AlertDialog(
            onDismissRequest = { if (ui.action == null) { leaveTarget = null; vm.clearFeedback() } },
            title = { Text("leave ${space.name}?") },
            text = {
                Column {
                    Text("You'll lose access to its channels until someone invites you back.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.leave(space.id) { app.refreshSpaces(); leaveTarget = null } },
                    enabled = ui.action == null,
                ) { ActionLabel(ui.action == SpaceAction.Leave, "leave") }
            },
            dismissButton = {
                TextButton(onClick = { leaveTarget = null; vm.clearFeedback() }, enabled = ui.action == null) { Text("cancel") }
            },
        )
    }

    renameChannelTarget?.let { (space, channel) ->
        var channelName by rememberSaveable(channel.id) { mutableStateOf(channel.name ?: "") }
        val busy = ui.action != null
        val submit: () -> Unit = {
            vm.renameChannel(space.id, channel.id, channelName) {
                renameChannelTarget = null
                app.refreshSpaces()
            }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) { renameChannelTarget = null; vm.clearFeedback() } },
            title = { Text("rename channel") },
            text = {
                Column {
                    OutlinedTextField(
                        value = channelName,
                        onValueChange = { channelName = it.take(32); vm.clearFeedback() },
                        label = { Text("channel name") },
                        prefix = { Text(if (channel.kind == "voice") "🔊 " else "#") },
                        supportingText = { Text("${channelName.length}/32") },
                        enabled = !busy,
                        singleLine = true,
                        shape = Corners.input,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                    )
                    AnimatedVisibility(
                        visible = ui.error != null,
                        enter = fadeIn(PigeonMotion.snappy()),
                        exit = fadeOut(PigeonMotion.snappy()),
                    ) {
                        Text(ui.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s))
                    }
                }
            },
            confirmButton = {
                Button(onClick = submit, enabled = !busy) {
                    ActionLabel(ui.action == SpaceAction.RenameChannel, "rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameChannelTarget = null; vm.clearFeedback() }, enabled = !busy) { Text("cancel") }
            },
        )
    }

    deleteChannelTarget?.let { (space, channel) ->
        AlertDialog(
            onDismissRequest = { if (ui.action == null) { deleteChannelTarget = null; vm.clearFeedback() } },
            title = { Text("delete #${channel.name ?: "channel"}?") },
            text = {
                Column {
                    Text("This removes the channel and its messages for everyone. This cannot be undone.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteChannel(space.id, channel.id) {
                            deleteChannelTarget = null
                            app.refreshSpaces()
                        }
                    },
                    enabled = ui.action == null,
                ) { ActionLabel(ui.action == SpaceAction.DeleteChannel, "delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteChannelTarget = null; vm.clearFeedback() }, enabled = ui.action == null) { Text("cancel") }
            },
        )
    }
}

private data class ChannelMenuActions(
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun ChannelOverflowMenu(actions: ChannelMenuActions, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.MoreVert, "channel options", Modifier.size(18.dp), tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("rename") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) },
                onClick = { open = false; actions.onRename() },
            )
            DropdownMenuItem(
                text = { Text("delete") },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; actions.onDelete() },
            )
        }
    }
}

private const val MAX_ICON_BYTES = 8 * 1024 * 1024

private data class PickedIcon(val bytes: ByteArray, val type: String)

private fun readIconImage(context: Context, uri: Uri): PickedIcon {
    val resolver = context.contentResolver
    val type = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.startsWith("image/") && it != "image/svg+xml" }
        ?: throw IllegalArgumentException("please choose an image")
    val output = ByteArrayOutputStream(minOf(MAX_ICON_BYTES, 64 * 1024))
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ICON_BYTES) throw IllegalArgumentException("image must be under 8mb")
            output.write(buffer, 0, read)
        }
    } ?: throw IllegalArgumentException("couldn't open that image")
    if (output.size() == 0) throw IllegalArgumentException("that image is empty")
    return PickedIcon(output.toByteArray(), type)
}

@Composable
private fun ActionLabel(working: Boolean, label: String) {
    if (working) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("working...", Modifier.padding(start = Spacing.s))
    } else Text(label)
}

@Composable
private fun SpaceCard(
    space: SpaceDto,
    modifier: Modifier = Modifier,
    iconUrl: String?,
    iconBusy: Boolean,
    singleNest: Boolean = false,
    onChangeIcon: (() -> Unit)?,
    onInvite: () -> Unit,
    onOpenChannel: (id: String, name: String, kind: String) -> Unit,
    onAddChannel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onLeave: (() -> Unit)? = null,
    channelMenu: ((app.pigeonsms.network.ChannelDto) -> ChannelMenuActions)? = null,
) {

    // a lone nest auto-expands so there's nothing to click through.
    var expanded by rememberSaveable(space.id) { mutableStateOf(singleNest) }
    val totalUnread = space.channels.sumOf { it.unread }

    Column(
        modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickableScale { expanded = !expanded }
            .padding(Spacing.l),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .then(
                        if (onChangeIcon != null) {
                            Modifier.clickableScale(onClick = onChangeIcon)
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = "${space.name} icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text(space.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (iconBusy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                } else if (onChangeIcon != null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PhotoCamera,
                            "change ${space.name} icon",
                            Modifier.size(12.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                Text(space.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    MemberChip(space.member_count)
                    if (space.role != "member") {
                        Spacer(Modifier.width(Spacing.xs))
                        Text(space.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                space.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs))
                }
            }
            IconButton(onClick = onInvite) { Icon(Icons.Outlined.PersonAdd, "invite to ${space.name}", tint = MaterialTheme.colorScheme.primary) }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "delete space", tint = MaterialTheme.colorScheme.error) }
            }
            if (onLeave != null) {
                IconButton(onClick = onLeave) { Icon(Icons.AutoMirrored.Outlined.Logout, "leave space", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (!expanded && totalUnread > 0) {
                Spacer(Modifier.width(Spacing.xs))
                UnreadPill(totalUnread)
            }
            val chevronRotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "spaceChevron")
            Icon(
                Icons.Outlined.ExpandMore,
                if (expanded) "collapse channels" else "show channels",
                Modifier.padding(start = Spacing.xs).size(24.dp).rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "channels",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (onAddChannel != null) {
                        IconButton(onClick = onAddChannel) {
                            Icon(Icons.Outlined.Add, "add channel to ${space.name}", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (space.channels.isEmpty()) {
                    Text("no channels yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s))
                } else {

                    // a primary-tinted wash + pill instead of shouting with boxes.
                    Column(Modifier.padding(start = Spacing.s, top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        space.channels.forEach { channel ->
                            val unread = channel.unread > 0
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clip(Corners.button)
                                    .background(if (unread) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                                    .clickableScale { onOpenChannel(channel.id, channel.name ?: "channel", channel.kind) }
                                    .padding(start = Spacing.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    when (channel.kind.lowercase()) {
                                        "forum" -> Icons.Outlined.Forum
                                        "voice" -> Icons.Outlined.VolumeUp
                                        else -> Icons.Outlined.Tag
                                    },
                                    null,
                                    Modifier.size(20.dp),
                                    tint = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    channel.name ?: "channel",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (unread) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(start = Spacing.m),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (channel.kind != "text") Text(channel.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.s))
                                if (unread) UnreadPill(channel.unread)
                                channelMenu?.let { ChannelOverflowMenu(it(channel), MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberChip(count: Int) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.s, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun NovaSpaceCard(
    space: SpaceDto,
    modifier: Modifier = Modifier,
    iconUrl: String?,
    iconBusy: Boolean,
    singleNest: Boolean = false,
    onChangeIcon: (() -> Unit)?,
    onInvite: () -> Unit,
    onOpenChannel: (id: String, name: String, kind: String) -> Unit,
    onAddChannel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onLeave: (() -> Unit)? = null,
    channelMenu: ((app.pigeonsms.network.ChannelDto) -> ChannelMenuActions)? = null,
) {
    var expanded by rememberSaveable(space.id) { mutableStateOf(singleNest) }
    val totalUnread = space.channels.sumOf { it.unread }
    val accent = MaterialTheme.colorScheme.primary
    val cardShape = NovaCorners.card
    Column(
        modifier
            .fillMaxWidth()
            .novaElevation(
                shape = cardShape,
                tint = MaterialTheme.colorScheme.surfaceContainer,
                accent = accent,
                accented = totalUnread > 0,
                glow = totalUnread > 0,
            ),
    ) {

        // + a glowing accent unread badge overlay.
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.6f)
                    .background(Brush.linearGradient(NovaGradients.coverMesh(accent)))
                    .then(if (onChangeIcon != null) Modifier.clickableScale(onClick = onChangeIcon) else Modifier),
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = "${space.name} icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // bottom scrim so the title area reads over any cover image / mesh
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f)),
                            ),
                        ),
                )
                if (iconBusy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
            if (totalUnread > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.m)
                        .novaHalo(accent, alpha = NovaDepth.glowStrong)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(NovaGradients.cta(accent)))
                        .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                ) {
                    NovaAnimatedCount(
                        count = totalUnread.coerceAtMost(999),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (onChangeIcon != null && !iconBusy) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.s)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(Spacing.xs),
                ) {
                    Icon(Icons.Outlined.PhotoCamera, "change ${space.name} icon", Modifier.size(16.dp), tint = Color.White)
                }
            }
        }

        Column(Modifier.padding(Spacing.l)) {
            val chevronRotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "novaChevron")
            Row(Modifier.fillMaxWidth().clickableScale { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    space.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!expanded && totalUnread > 0) {
                    UnreadPill(totalUnread)
                    Spacer(Modifier.width(Spacing.xs))
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    if (expanded) "collapse channels" else "show channels",
                    Modifier.size(26.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            space.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovaTag {
                    Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${space.member_count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (space.role != "member") {
                    // role is a deliberate cyan touchpoint (secondary accent).
                    NovaTag {
                        Text(
                            space.role,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onInvite) { Icon(Icons.Outlined.PersonAdd, "invite to ${space.name}", tint = MaterialTheme.colorScheme.primary) }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "delete space", tint = MaterialTheme.colorScheme.error) }
                if (onLeave != null) IconButton(onClick = onLeave) { Icon(Icons.AutoMirrored.Outlined.Logout, "leave space", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NovaSectionLabel(
                            "channels",
                            accent = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (onAddChannel != null) {
                            IconButton(onClick = onAddChannel) {
                                Icon(Icons.Outlined.Add, "add channel to ${space.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (space.channels.isEmpty()) {
                        Text("no channels yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs))
                    } else {
                        Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            space.channels.forEach { channel ->
                                NovaChannelRow(
                                    channel = channel,
                                    onClick = { onOpenChannel(channel.id, channel.name ?: "channel", channel.kind) },
                                    menu = channelMenu?.invoke(channel),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovaChannelRow(channel: app.pigeonsms.network.ChannelDto, onClick: () -> Unit, menu: ChannelMenuActions? = null) {
    val unread = channel.unread > 0
    val accent = MaterialTheme.colorScheme.primary
    val shape = NovaCorners.button

    // switch; read rows sit on a lifted-top surface, unread rows on an accent wash.
    val fillTop by animateColorAsState(
        targetValue = if (unread) accent else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = NovaMotion.emphasized(),
        label = "channelFill",
    )
    val fillBottom by animateColorAsState(
        targetValue = if (unread) NovaColors.IrisCyanDeep else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = NovaMotion.emphasized(),
        label = "channelFillB",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
            .novaGlow(shape, accent, active = unread)
            .clickableScale(onClick = onClick)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onColor = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val subColor = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        // read state: kind icon on a cyan-tinted disc (secondary touchpoint);
        // unread: frosted-white disc over the accent wash.
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                .background(if (unread) Color.White.copy(alpha = 0.20f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (channel.kind.lowercase()) {
                    "forum" -> Icons.Outlined.Forum
                    "voice" -> Icons.Outlined.VolumeUp
                    else -> Icons.Outlined.Tag
                },
                null,
                Modifier.size(16.dp),
                tint = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            channel.name ?: "channel",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
            color = onColor,
            modifier = Modifier.weight(1f).padding(start = Spacing.m),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (unread) {
            UnreadPill(channel.unread)
        } else if (menu == null) {
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = subColor)
        }
        menu?.let { ChannelOverflowMenu(it, if (unread) MaterialTheme.colorScheme.onPrimary else subColor) }
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun ExpSpaceCard(
    space: SpaceDto,
    modifier: Modifier = Modifier,
    iconUrl: String?,
    iconBusy: Boolean,
    singleNest: Boolean = false,
    onChangeIcon: (() -> Unit)?,
    onInvite: () -> Unit,
    onOpenChannel: (id: String, name: String, kind: String) -> Unit,
    onAddChannel: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onLeave: (() -> Unit)? = null,
    channelMenu: ((app.pigeonsms.network.ChannelDto) -> ChannelMenuActions)? = null,
) {
    var expanded by rememberSaveable(space.id) { mutableStateOf(singleNest) }
    val totalUnread = space.channels.sumOf { it.unread }
    Column(
        modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {

        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.6f)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .then(if (onChangeIcon != null) Modifier.clickableScale(onClick = onChangeIcon) else Modifier),
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = "${space.name} icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (iconBusy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
            if (totalUnread > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.m)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                ) {
                    Text(
                        if (totalUnread > 99) "99+" else "$totalUnread",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (onChangeIcon != null && !iconBusy) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.s)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(Spacing.xs),
                ) {
                    Icon(Icons.Outlined.PhotoCamera, "change ${space.name} icon", Modifier.size(16.dp), tint = Color.White)
                }
            }
        }

        Column(Modifier.padding(Spacing.l)) {
            val chevronRotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "expChevron")
            Row(Modifier.fillMaxWidth().clickableScale { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    space.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!expanded && totalUnread > 0) {
                    UnreadPill(totalUnread)
                    Spacer(Modifier.width(Spacing.xs))
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    if (expanded) "collapse channels" else "show channels",
                    Modifier.size(26.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            space.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpChip {
                    Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${space.member_count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.xs))
                }
                if (space.role != "member") {
                    ExpChip(accent = true) {
                        Text(space.role, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onInvite) { Icon(Icons.Outlined.PersonAdd, "invite to ${space.name}", tint = MaterialTheme.colorScheme.primary) }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "delete space", tint = MaterialTheme.colorScheme.error) }
                if (onLeave != null) IconButton(onClick = onLeave) { Icon(Icons.AutoMirrored.Outlined.Logout, "leave space", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "CHANNELS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (onAddChannel != null) {
                            IconButton(onClick = onAddChannel) {
                                Icon(Icons.Outlined.Add, "add channel to ${space.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (space.channels.isEmpty()) {
                        Text("no channels yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs))
                    } else {
                        Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            space.channels.forEach { channel ->
                                ExpChannelRow(
                                    channel = channel,
                                    onClick = { onOpenChannel(channel.id, channel.name ?: "channel", channel.kind) },
                                    menu = channelMenu?.invoke(channel),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpChannelRow(channel: app.pigeonsms.network.ChannelDto, onClick: () -> Unit, menu: ChannelMenuActions? = null) {
    val unread = channel.unread > 0
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(Corners.button)
            .background(if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickableScale(onClick = onClick)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onColor = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        val subColor = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                .background(if (unread) Color.White.copy(alpha = 0.20f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (channel.kind.lowercase()) {
                    "forum" -> Icons.Outlined.Forum
                    "voice" -> Icons.Outlined.VolumeUp
                    else -> Icons.Outlined.Tag
                },
                null,
                Modifier.size(16.dp),
                tint = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            channel.name ?: "channel",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
            color = onColor,
            modifier = Modifier.weight(1f).padding(start = Spacing.m),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (unread) {
            UnreadPill(channel.unread)
        } else if (menu == null) {
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = subColor)
        }
        menu?.let { ChannelOverflowMenu(it, if (unread) MaterialTheme.colorScheme.onPrimary else subColor) }
    }
}

@Composable
private fun ExpChip(accent: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
