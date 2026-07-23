package app.pigeonsms.ui.spaces

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.components.NovaHero
import app.pigeonsms.design.components.NovaIconBadgeButton
import app.pigeonsms.design.components.NovaTag
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.ui.AppViewModel
import app.pigeonsms.ui.UnreadPill
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.LoadingState
import app.pigeonsms.ui.util.ScreenHeader
import app.pigeonsms.ui.util.clickableScale
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(
    app: AppViewModel,
    onOpenNest: (spaceId: String) -> Unit,
    onOpenChannel: (id: String, name: String, kind: String) -> Unit,
) {
    val vm: SpacesViewModel = pigeonVm { c, _ -> SpacesViewModel(c.socialRepository, c.api) }
    val home by app.home.collectAsState()
    val ui by vm.ui.collectAsState()
    val skin = LocalUiSkin.current

    val galaxy = skin == UiSkin.Galaxy
    val novaSkin = skin == UiSkin.Nova
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.refreshSpaces() }
    LaunchedEffect(ui.createdChannel?.id) {
        val channel = ui.createdChannel ?: return@LaunchedEffect
        app.refreshSpaces()
        vm.consumeCreatedChannel()
        onOpenChannel(channel.id, channel.name, channel.kind)
    }

    // the clean nest-list below and tap through to a dedicated channels screen.
    if (home.spaces.size == 1) {
        NestChannelsScreen(
            app = app,
            spaceId = home.spaces.first().id,
            onBack = {},
            onOpenChannel = onOpenChannel,
            embedded = true,
            onCreateOrJoin = { vm.clearFeedback(); sheetOpen = true },
        )
        CreateJoinNestSheet(vm, app, sheetOpen) { sheetOpen = false }
        return
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
            visible = ui.error != null && !sheetOpen,
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

                    NestRow(
                        space = space,
                        modifier = Modifier.itemAppear(index),
                        iconUrl = app.mediaUrl(space.icon_key),
                        galaxy = galaxy,
                        novaSkin = novaSkin,
                        onClick = { onOpenNest(space.id) },
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

    CreateJoinNestSheet(vm, app, sheetOpen) { sheetOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateJoinNestSheet(
    vm: SpacesViewModel,
    app: AppViewModel,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    if (!open) return
    val ui by vm.ui.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val busy = ui.action != null
    ModalBottomSheet(
        onDismissRequest = { if (!busy) { onDismiss(); vm.clearFeedback() } },
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
                onClick = { vm.create(name) { app.refreshSpaces(); onDismiss() } },
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
                onClick = { vm.join(code) { app.refreshSpaces(); onDismiss() } },
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

@Composable
private fun NestRow(
    space: SpaceDto,
    modifier: Modifier = Modifier,
    iconUrl: String?,
    galaxy: Boolean,
    novaSkin: Boolean,
    onClick: () -> Unit,
) {
    val totalUnread = space.channels.sumOf { it.unread }
    val expressive = galaxy || novaSkin
    val accent = MaterialTheme.colorScheme.primary
    val base = if (expressive) {
        Modifier
            .fillMaxWidth()
            .novaElevation(
                shape = NovaCorners.card,
                tint = MaterialTheme.colorScheme.surfaceContainer,
                accent = accent,
                accented = totalUnread > 0,
                glow = totalUnread > 0,
            )
    } else {
        Modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    }
    Row(
        modifier
            .then(base)
            .clickableScale(onClick = onClick)
            .padding(Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
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
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
            Text(
                space.name,
                style = if (expressive) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                MemberChip(space.member_count)
                if (space.role != "member") {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(space.role, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            space.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs))
            }
        }
        if (totalUnread > 0) {
            UnreadPill(totalUnread)
            Spacer(Modifier.width(Spacing.xs))
        }
        Icon(
            Icons.Outlined.ChevronRight,
            "open ${space.name}",
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Composable
private fun ActionLabel(working: Boolean, label: String) {
    if (working) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("working...", Modifier.padding(start = Spacing.s))
    } else Text(label)
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
