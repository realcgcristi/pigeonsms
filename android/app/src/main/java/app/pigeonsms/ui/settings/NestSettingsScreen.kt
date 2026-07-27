package app.pigeonsms.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.ChannelDto
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.ui.AppViewModel
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.spaces.SpaceAction
import app.pigeonsms.ui.spaces.SpacesViewModel
import coil.compose.AsyncImage

/**
 * Central "bird nests" management page in the settings style. Lists every nest the
 * user is in; for OWNED nests each channel can be renamed or deleted inline, and
 * non-owned nests get a "leave nest" action. Reuses the settings primitives
 * (GroupCard / Group / SettingsSubHeader) and the spaces data + actions the app
 * already uses (AppViewModel.home.spaces + SpacesViewModel). 3-skin aware via those
 * helpers.
 */
@Composable
fun NestSettingsScreen(app: AppViewModel, onBack: () -> Unit) {
    val vm: SpacesViewModel = pigeonVm { c, _ -> SpacesViewModel(c.socialRepository, c.api) }
    val home by app.home.collectAsState()
    val ui by vm.ui.collectAsState()
    val nova = LocalUiSkin.current != UiSkin.Classic

    // pending dialogs
    var renameTarget by remember { mutableStateOf<Pair<SpaceDto, ChannelDto>?>(null) }
    var deleteChannelTarget by remember { mutableStateOf<Pair<SpaceDto, ChannelDto>?>(null) }
    var leaveTarget by remember { mutableStateOf<SpaceDto?>(null) }

    LaunchedEffect(Unit) { app.refreshSpaces() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSubHeader("bird nests", onBack)
        Column(Modifier.padding(horizontal = Spacing.l)) {
            AnimatedVisibility(
                visible = ui.error != null && renameTarget == null && deleteChannelTarget == null && leaveTarget == null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    ui.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
            }

            if (home.spaces.isEmpty()) {
                Group("your nests")
                GroupCard {
                    Text(
                        if (home.spacesLoading) "loading bird nests…" else "you're not in any bird nests yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.l),
                    )
                }
            } else {
                home.spaces.forEach { space ->
                    val owner = space.role == "owner"
                    Group(space.name)
                    GroupCard {
                        // nest header row: icon + name + role, with leave for non-owned nests
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = if (nova) 68.dp else 56.dp)
                                .padding(horizontal = Spacing.l, vertical = Spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NestIcon(space, app.mediaUrl(space.icon_key))
                            Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                                Text(
                                    space.name,
                                    style = if (nova) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${space.role} · ${space.member_count} ${if (space.member_count == 1) "member" else "members"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!owner) {
                                IconButton(onClick = { vm.clearFeedback(); leaveTarget = space }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.Logout,
                                        "leave ${space.name}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // channels — owners get inline rename/delete controls
                        if (space.channels.isNotEmpty()) RowDivider()
                        space.channels.forEachIndexed { index, channel ->
                            if (index > 0) RowDivider()
                            ChannelManageRow(
                                channel = channel,
                                manageable = owner,
                                onRename = { vm.clearFeedback(); renameTarget = space to channel },
                                onDelete = { vm.clearFeedback(); deleteChannelTarget = space to channel },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.huge))
        }
    }

    // rename channel dialog
    renameTarget?.let { (space, channel) ->
        var name by rememberSaveable(channel.id) { mutableStateOf(channel.name ?: "") }
        val busy = ui.action != null
        AlertDialog(
            onDismissRequest = { if (!busy) { renameTarget = null; vm.clearFeedback() } },
            title = { Text("rename channel") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(32); vm.clearFeedback() },
                        label = { Text("channel name") },
                        prefix = { Text("#") },
                        supportingText = { Text("${name.length}/32") },
                        enabled = !busy,
                        singleLine = true,
                        shape = if (nova) NovaCorners.input else Corners.input,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s))
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.renameChannel(space.id, channel.id, name) { app.refreshSpaces(); renameTarget = null } },
                    enabled = !busy,
                ) { ActionLabel(ui.action == SpaceAction.RenameChannel, "rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null; vm.clearFeedback() }, enabled = !busy) { Text("cancel") }
            },
        )
    }

    // delete channel dialog
    deleteChannelTarget?.let { (space, channel) ->
        val busy = ui.action != null
        AlertDialog(
            onDismissRequest = { if (!busy) { deleteChannelTarget = null; vm.clearFeedback() } },
            title = { Text("delete #${channel.name ?: "channel"}?") },
            text = {
                Column {
                    Text("This removes the channel and its messages for everyone in ${space.name}. This cannot be undone.")
                    if (ui.error != null) Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.m))
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.deleteChannel(space.id, channel.id) { app.refreshSpaces(); deleteChannelTarget = null } },
                    enabled = !busy,
                ) { ActionLabel(ui.action == SpaceAction.DeleteChannel, "delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteChannelTarget = null; vm.clearFeedback() }, enabled = !busy) { Text("cancel") }
            },
        )
    }

    // leave nest dialog
    leaveTarget?.let { space ->
        val busy = ui.action != null
        AlertDialog(
            onDismissRequest = { if (!busy) { leaveTarget = null; vm.clearFeedback() } },
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
                    enabled = !busy,
                ) { ActionLabel(ui.action == SpaceAction.Leave, "leave") }
            },
            dismissButton = {
                TextButton(onClick = { leaveTarget = null; vm.clearFeedback() }, enabled = !busy) { Text("cancel") }
            },
        )
    }
}

/** Small round nest icon: uploaded image or a monogram fallback. */
@Composable
private fun NestIcon(space: SpaceDto, iconUrl: String?) {
    Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = "${space.name} icon",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(space.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** A single channel row inside a nest's GroupCard. Owners see rename + delete. */
@Composable
private fun ChannelManageRow(
    channel: ChannelDto,
    manageable: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val nova = LocalUiSkin.current != UiSkin.Classic
    val kindIcon = when (channel.kind.lowercase()) {
        "forum" -> Icons.Outlined.Forum
        "voice" -> Icons.Outlined.VolumeUp
        else -> Icons.Outlined.Tag
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (manageable) Modifier.clickable(onClick = onRename) else Modifier)
            .heightIn(min = if (nova) 60.dp else 52.dp)
            .padding(start = Spacing.l + 40.dp + Spacing.m, end = Spacing.l, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            channel.name ?: "channel",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = Spacing.m),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (manageable) {
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.Edit, "rename ${channel.name ?: "channel"}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, "delete ${channel.name ?: "channel"}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Inline busy/label for dialog confirm buttons — mirrors the spaces screen. */
@Composable
private fun ActionLabel(working: Boolean, label: String) {
    if (working) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("working…", Modifier.padding(start = Spacing.s))
    } else {
        Text(label)
    }
}
