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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.components.NovaSectionLabel
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaMotion
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.novaGlow
import app.pigeonsms.network.ChannelDto
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.ui.AppViewModel
import app.pigeonsms.ui.UnreadPill
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.LiquidSegmented
import app.pigeonsms.ui.util.LoadingState
import app.pigeonsms.ui.util.clickableScale
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated screen for a single bird nest — its channel list plus the owner
 * actions (invite, change icon, add channel, demolish/leave, per-channel
 * rename/delete). Reached by tapping a nest row in [SpacesScreen] when there is
 * more than one nest. Reuses the same [SpacesViewModel] callbacks so all nest
 * management works identically to the old inline card. Styled for all three
 * skins (classic / nova / galaxy) via [LocalUiSkin].
 */
/**
 * @param embedded when true this is the single-nest inline view rendered directly
 *   inside the bird-nests tab (no back button; the header shows a create/join "+"
 *   via [onCreateOrJoin]). When false it's a pushed navigation destination with a
 *   back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NestChannelsScreen(
    app: AppViewModel,
    spaceId: String,
    onBack: () -> Unit,
    onOpenChannel: (id: String, name: String, kind: String) -> Unit,
    embedded: Boolean = false,
    onCreateOrJoin: (() -> Unit)? = null,
) {
    val vm: SpacesViewModel = pigeonVm(key = "nest-$spaceId") { c, _ -> SpacesViewModel(c.socialRepository, c.api) }
    val home by app.home.collectAsState()
    val ui by vm.ui.collectAsState()
    val skin = LocalUiSkin.current
    val expressive = skin == UiSkin.Galaxy || skin == UiSkin.Nova

    val space = home.spaces.firstOrNull { it.id == spaceId }

    var createChannelOpen by rememberSaveable(spaceId) { mutableStateOf(false) }
    var demolishOpen by rememberSaveable(spaceId) { mutableStateOf(false) }
    var leaveOpen by rememberSaveable(spaceId) { mutableStateOf(false) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var renameChannelTarget by remember { mutableStateOf<ChannelDto?>(null) }
    var deleteChannelTarget by remember { mutableStateOf<ChannelDto?>(null) }
    var iconTarget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val current = space
        if (uri == null || current == null) {
            iconTarget = false
        } else {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { readNestIconImage(context, uri) }
                }.onSuccess { picked ->
                    vm.changeIcon(current.id, picked.bytes, picked.type) {
                        iconTarget = false
                        app.refreshSpaces()
                    }
                }.onFailure { error ->
                    iconTarget = false
                    vm.reportError(error.message ?: "couldn't read that image")
                }
            }
        }
    }

    LaunchedEffect(Unit) { app.refreshSpaces() }
    LaunchedEffect(ui.createdChannel?.id) {
        val channel = ui.createdChannel ?: return@LaunchedEffect
        app.refreshSpaces()
        createChannelOpen = false
        vm.consumeCreatedChannel()
        onOpenChannel(channel.id, channel.name, channel.kind)
    }
    // If the nest vanished (demolished or left) while pushed as its own screen,
    // pop back to the nest list. Embedded (single-nest) view stays put — the tab
    // itself re-renders its empty/list state once home.spaces updates.
    LaunchedEffect(space == null, home.spacesLoading, embedded) {
        if (!embedded && space == null && !home.spacesLoading) onBack()
    }

    val isOwner = space?.role == "owner"
    val canManageIcon = space?.role == "owner" || space?.role == "admin"

    val onChangeIcon: (() -> Unit)? = if (space != null && canManageIcon) {
        {
            vm.clearFeedback()
            iconTarget = true
            iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    } else null
    val onInvite: () -> Unit = { space?.let { vm.invite(it.id) { code -> inviteCode = code } } }
    val onAddChannel: (() -> Unit)? = if (isOwner) { { vm.clearFeedback(); createChannelOpen = true } } else null
    val onDemolish: (() -> Unit)? = if (isOwner) { { vm.clearFeedback(); demolishOpen = true } } else null
    val onLeave: (() -> Unit)? = if (space != null && !isOwner) { { vm.clearFeedback(); leaveOpen = true } } else null
    val channelMenu: ((ChannelDto) -> NestChannelMenuActions)? = if (isOwner) {
        { channel ->
            NestChannelMenuActions(
                onRename = { vm.clearFeedback(); renameChannelTarget = channel },
                onDelete = { vm.clearFeedback(); deleteChannelTarget = channel },
            )
        }
    } else null

    Column(Modifier.fillMaxSize()) {
        // Header: back (or create/join "+" when embedded) + nest identity + actions.
        NestHeader(
            space = space,
            iconUrl = space?.let { app.mediaUrl(it.icon_key) },
            iconBusy = ui.action == SpaceAction.ChangeIcon && iconTarget,
            expressive = expressive,
            embedded = embedded,
            onBack = onBack,
            onCreateOrJoin = onCreateOrJoin,
            onChangeIcon = onChangeIcon,
            onInvite = onInvite,
            onDemolish = onDemolish,
            onLeave = onLeave,
        )

        AnimatedVisibility(
            visible = ui.error != null && !createChannelOpen && !demolishOpen && !leaveOpen &&
                renameChannelTarget == null && deleteChannelTarget == null,
            enter = fadeIn(PigeonMotion.snappy()),
            exit = fadeOut(PigeonMotion.snappy()),
        ) {
            Text(
                ui.error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
            )
        }

        when {
            space == null && home.spacesLoading -> LoadingState("loading channels")
            space == null -> Empty("nest not found", "it may have been demolished", icon = Icons.Outlined.Forum)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Spacing.s, bottom = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (expressive) {
                            NovaSectionLabel("channels", accent = true, modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                "channels",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (onAddChannel != null) {
                            IconButton(onClick = onAddChannel) {
                                Icon(Icons.Outlined.Add, "add channel to ${space.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (space.channels.isEmpty()) {
                    item {
                        Text(
                            "no channels yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.s),
                        )
                    }
                } else {
                    itemsIndexed(space.channels, key = { _, ch -> ch.id }) { index, channel ->
                        NestChannelRow(
                            channel = channel,
                            expressive = expressive,
                            modifier = Modifier.itemAppear(index),
                            onClick = { onOpenChannel(channel.id, channel.name ?: "channel", channel.kind) },
                            menu = channelMenu?.invoke(channel),
                        )
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
                Text(code, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
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

    if (createChannelOpen && space != null) {
        var channelName by rememberSaveable(space.id) { mutableStateOf("") }
        var kindIndex by rememberSaveable(space.id) { mutableStateOf(0) }
        val kinds = listOf("text", "voice", "forum")
        val kind = kinds[kindIndex.coerceIn(0, kinds.lastIndex)]
        val busy = ui.action != null
        val submit: () -> Unit = { vm.createChannel(space.id, channelName, kind) }
        AlertDialog(
            onDismissRequest = { if (!busy) { createChannelOpen = false; vm.clearFeedback() } },
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
                        Text(ui.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s))
                    }
                }
            },
            confirmButton = {
                Button(onClick = submit, enabled = !busy) { NestActionLabel(ui.action == SpaceAction.CreateChannel, "create") }
            },
            dismissButton = {
                TextButton(onClick = { createChannelOpen = false; vm.clearFeedback() }, enabled = !busy) { Text("cancel") }
            },
        )
    }

    if (demolishOpen && space != null) {
        AlertDialog(
            onDismissRequest = { if (ui.action == null) { demolishOpen = false; vm.clearFeedback() } },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("demolish ${space.name}?") },
            text = {
                Column {
                    Text("This tears the whole nest down — every channel and message — for everyone. There's no rebuilding it.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.delete(space.id) {
                            demolishOpen = false
                            app.refreshSpaces()
                            onBack()
                        }
                    },
                    enabled = ui.action == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { NestActionLabel(ui.action == SpaceAction.Delete, "demolish") }
            },
            dismissButton = {
                TextButton(onClick = { demolishOpen = false; vm.clearFeedback() }, enabled = ui.action == null) { Text("keep it") }
            },
        )
    }

    if (leaveOpen && space != null) {
        AlertDialog(
            onDismissRequest = { if (ui.action == null) { leaveOpen = false; vm.clearFeedback() } },
            title = { Text("leave ${space.name}?") },
            text = {
                Column {
                    Text("You'll lose access to its channels until someone invites you back.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.leave(space.id) {
                            leaveOpen = false
                            app.refreshSpaces()
                            onBack()
                        }
                    },
                    enabled = ui.action == null,
                ) { NestActionLabel(ui.action == SpaceAction.Leave, "leave") }
            },
            dismissButton = {
                TextButton(onClick = { leaveOpen = false; vm.clearFeedback() }, enabled = ui.action == null) { Text("cancel") }
            },
        )
    }

    renameChannelTarget?.let { channel ->
        val current = space ?: return@let
        var channelName by rememberSaveable(channel.id) { mutableStateOf(channel.name ?: "") }
        val busy = ui.action != null
        val submit: () -> Unit = {
            vm.renameChannel(current.id, channel.id, channelName) {
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
                Button(onClick = submit, enabled = !busy) { NestActionLabel(ui.action == SpaceAction.RenameChannel, "rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameChannelTarget = null; vm.clearFeedback() }, enabled = !busy) { Text("cancel") }
            },
        )
    }

    deleteChannelTarget?.let { channel ->
        val current = space ?: return@let
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
                        vm.deleteChannel(current.id, channel.id) {
                            deleteChannelTarget = null
                            app.refreshSpaces()
                        }
                    },
                    enabled = ui.action == null,
                ) { NestActionLabel(ui.action == SpaceAction.DeleteChannel, "delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteChannelTarget = null; vm.clearFeedback() }, enabled = ui.action == null) { Text("cancel") }
            },
        )
    }
}

/** Owner-only channel row actions surfaced via each channel's overflow menu. */
data class NestChannelMenuActions(
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun NestChannelOverflowMenu(actions: NestChannelMenuActions, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(androidx.compose.material.icons.Icons.Outlined.MoreVert, "channel options", Modifier.size(18.dp), tint = tint)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("rename") },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Edit, null, Modifier.size(18.dp)) },
                onClick = { open = false; actions.onRename() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("delete") },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; actions.onDelete() },
            )
        }
    }
}

/** Header band: back button, nest icon + name + meta, and the nest-level actions. */
@Composable
private fun NestHeader(
    space: SpaceDto?,
    iconUrl: String?,
    iconBusy: Boolean,
    expressive: Boolean,
    embedded: Boolean,
    onBack: () -> Unit,
    onCreateOrJoin: (() -> Unit)?,
    onChangeIcon: (() -> Unit)?,
    onInvite: () -> Unit,
    onDemolish: (() -> Unit)?,
    onLeave: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Spacing.s, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Pushed screen gets a back arrow; the embedded single-nest view has
            // nowhere to go back to, so it leads with the nest icon directly.
            if (!embedded) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back to nests", tint = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Spacer(Modifier.width(Spacing.xs))
            }
            // Nest icon.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .then(if (onChangeIcon != null) Modifier.clickableScale(onClick = onChangeIcon) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = iconUrl,
                        contentDescription = "${space?.name.orEmpty()} icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text((space?.name ?: "?").take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (iconBusy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                } else if (onChangeIcon != null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, "change icon", Modifier.size(10.dp), tint = Color.White)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = Spacing.m)) {
                Text(
                    space?.name ?: "bird nest",
                    style = if (expressive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (space != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xxs)) {
                        Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${space.member_count}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.xxs),
                        )
                        if (space.role != "member") {
                            Spacer(Modifier.width(Spacing.s))
                            Text(space.role, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (embedded && onCreateOrJoin != null) {
                IconButton(onClick = onCreateOrJoin) {
                    Icon(Icons.Outlined.Add, "create or join a bird nest", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        // Nest actions row.
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs, start = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NestActionChip(
                icon = Icons.Outlined.PersonAdd,
                label = "invite",
                onClick = onInvite,
                tint = MaterialTheme.colorScheme.primary,
            )
            if (onDemolish != null) {
                NestActionChip(
                    icon = Icons.Outlined.DeleteForever,
                    label = "demolish nest",
                    onClick = onDemolish,
                    tint = MaterialTheme.colorScheme.error,
                    destructive = true,
                )
            }
            if (onLeave != null) {
                NestActionChip(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = "leave",
                    onClick = onLeave,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NestActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color,
    destructive: Boolean = false,
) {
    val bg = if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickableScale(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/**
 * A single channel row. Classic uses the calm tinted-wash style; the expressive
 * skins (nova/galaxy) get the filled accent / glow treatment. Same tap + owner
 * overflow behavior as the old inline lists.
 */
@Composable
private fun NestChannelRow(
    channel: ChannelDto,
    expressive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    menu: NestChannelMenuActions?,
) {
    val unread = channel.unread > 0
    if (expressive) {
        val accent = MaterialTheme.colorScheme.primary
        val shape = NovaCorners.button
        val fillTop by animateColorAsState(
            targetValue = if (unread) accent else MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = NovaMotion.emphasized(),
            label = "nestChannelFill",
        )
        val fillBottom by animateColorAsState(
            targetValue = if (unread) NovaColors.IrisCyanDeep else MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = NovaMotion.emphasized(),
            label = "nestChannelFillB",
        )
        Row(
            modifier
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
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (unread) Color.White.copy(alpha = 0.20f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(channelKindIcon(channel.kind), null, Modifier.size(16.dp), tint = if (unread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary)
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
                Icon(androidx.compose.material.icons.Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = subColor)
            }
            menu?.let { NestChannelOverflowMenu(it, if (unread) MaterialTheme.colorScheme.onPrimary else subColor) }
        }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(Corners.button)
                .background(if (unread) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer)
                .clickableScale(onClick = onClick)
                .padding(horizontal = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                channelKindIcon(channel.kind),
                null,
                Modifier.size(20.dp),
                tint = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                channel.name ?: "channel",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (unread) FontWeight.SemiBold else null,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = Spacing.m),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (channel.kind != "text") Text(channel.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.s))
            if (unread) UnreadPill(channel.unread)
            menu?.let { NestChannelOverflowMenu(it, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun channelKindIcon(kind: String) = when (kind.lowercase()) {
    "forum" -> Icons.Outlined.Forum
    "voice" -> Icons.Outlined.VolumeUp
    else -> Icons.Outlined.Tag
}

@Composable
private fun NestActionLabel(working: Boolean, label: String) {
    if (working) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("working...", Modifier.padding(start = Spacing.s))
    } else Text(label)
}

private const val MAX_NEST_ICON_BYTES = 8 * 1024 * 1024

private data class PickedNestIcon(val bytes: ByteArray, val type: String)

private fun readNestIconImage(context: Context, uri: Uri): PickedNestIcon {
    val resolver = context.contentResolver
    val type = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.startsWith("image/") && it != "image/svg+xml" }
        ?: throw IllegalArgumentException("please choose an image")
    val output = java.io.ByteArrayOutputStream(minOf(MAX_NEST_ICON_BYTES, 64 * 1024))
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_NEST_ICON_BYTES) throw IllegalArgumentException("image must be under 8mb")
            output.write(buffer, 0, read)
        }
    } ?: throw IllegalArgumentException("couldn't open that image")
    if (output.size() == 0) throw IllegalArgumentException("that image is empty")
    return PickedNestIcon(output.toByteArray(), type)
}
