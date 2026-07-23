package app.pigeonsms.ui.forum

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.NovaDepth
import app.pigeonsms.design.theme.NovaGradients
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.heroAppear
import app.pigeonsms.design.theme.novaElevation
import app.pigeonsms.design.theme.novaHalo
import app.pigeonsms.design.theme.novaSurface
import app.pigeonsms.design.components.NovaAnimatedCount
import app.pigeonsms.design.components.NovaIconBadgeButton
import app.pigeonsms.design.components.NovaPillButton
import app.pigeonsms.design.components.NovaTag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.pigeonsms.network.ApiUser
import app.pigeonsms.network.ForumPostDto
import app.pigeonsms.network.ForumTagDto
import app.pigeonsms.network.MessageDto
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.LoadingState
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.smartTime
import kotlinx.coroutines.launch

@Composable
fun ForumScreen(
    channelId: String,
    title: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val vm: ForumViewModel = pigeonVm(key = "forum-$channelId") { c, _ ->
        ForumViewModel(c.api, c.socialRepository, c.gateway, channelId, title)
    }
    val ui by vm.ui.collectAsState()
    var composeOpen by rememberSaveable(channelId) { mutableStateOf(false) }
    var renameOpen by rememberSaveable(channelId) { mutableStateOf(false) }
    var tagDialogOpen by rememberSaveable(channelId) { mutableStateOf(false) }

    val liveTitle = ui.title.takeIf { it.isNotBlank() } ?: title

    BackHandler(enabled = ui.openPostId != null) { vm.closePost() }

    val galaxy = LocalUiSkin.current == UiSkin.Galaxy
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val headerSubtitle = if (ui.openPostId != null) {
                ui.thread.post?.let { forumPostTitle(it.metadata) } ?: "post"
            } else {
                val n = ui.posts.size
                if (n == 0) "forum" else if (n == 1) "1 post" else "$n posts"
            }

            // inside a thread) and only to the space owner.
            val onRename: (() -> Unit)? = if (ui.isOwner && ui.openPostId == null) {
                { renameOpen = true }
            } else null
            if (galaxy) {
                NovaForumHeader(
                    title = liveTitle,
                    subtitle = headerSubtitle,
                    onBack = { if (ui.openPostId != null) vm.closePost() else onBack() },
                    onRename = onRename,
                )
            } else {
                ForumHeader(
                    title = liveTitle,
                    subtitle = if (ui.openPostId != null) {
                        ui.thread.post?.let { forumPostTitle(it.metadata) } ?: "post"
                    } else {
                        "forum"
                    },
                    onBack = { if (ui.openPostId != null) vm.closePost() else onBack() },
                    onRename = onRename,
                )
            }

            val fadeSpec: FiniteAnimationSpec<Float> = PigeonMotion.snappy()
            val slideSpec: FiniteAnimationSpec<IntOffset> = PigeonMotion.snappy()
            AnimatedContent(
                targetState = ui.openPostId,
                transitionSpec = {
                    if (targetState != null) {
                        (fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { it / 3 })
                            .togetherWith(fadeOut(fadeSpec))
                    } else {
                        (fadeIn(fadeSpec) + slideInHorizontally(slideSpec) { -it / 3 })
                            .togetherWith(fadeOut(fadeSpec))
                    }
                },
                label = "forumListDetail",
                modifier = Modifier.fillMaxSize(),
            ) { openPostId ->
                if (openPostId == null) {
                    PostList(
                        ui = ui,
                        avatarUrl = vm::mediaUrl,
                        onSort = vm::setSort,
                        onRetry = { vm.refresh() },
                        onOpen = { vm.openPost(it.id) },
                        onOpenProfile = onOpenProfile,
                        onNewPost = { vm.clearCreateError(); composeOpen = true },
                        canDelete = vm::canDelete,
                        onDelete = vm::deletePost,
                        onTagFilter = vm::setTagFilter,
                        onLike = vm::toggleLike,
                        onCreateTag = { vm.clearTagError(); tagDialogOpen = true },
                    )
                } else {
                    ThreadView(
                        thread = ui.thread,
                        isOwner = ui.isOwner,
                        pinned = ui.openPostPinned,
                        marked = ui.openPostMarked,
                        markLabel = ui.openPostTag?.mark_label,
                        canMark = ui.isOwner || ui.openPostIsOp,
                        mentions = ui.mentionCandidates,
                        onMentionLookup = vm::loadMentionCandidates,
                        avatarUrl = vm::mediaUrl,
                        onRetry = { vm.openPost(openPostId) },
                        onOpenProfile = onOpenProfile,
                        onSend = { text, image, done -> vm.sendReply(text, image, done) },
                        canDelete = vm::canDelete,
                        onDelete = vm::deletePost,
                        onTogglePin = { vm.togglePin(openPostId) },
                        onToggleMark = { vm.toggleMark(openPostId) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = ui.openPostId == null,
            enter = if (galaxy) fadeIn(PigeonMotion.snappy()) + scaleIn(PigeonMotion.bouncy(), initialScale = 0.85f)
                    else fadeIn(PigeonMotion.snappy()),
            exit = if (galaxy) fadeOut(PigeonMotion.snappy()) + scaleOut(PigeonMotion.snappy(), targetScale = 0.85f)
                   else fadeOut(PigeonMotion.snappy()),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            if (galaxy) {
                NovaPillButton(
                    text = "new post",
                    onClick = { vm.clearCreateError(); composeOpen = true },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(Spacing.l)
                        .width(180.dp),
                    height = 56.dp,
                    leading = { Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)) },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { vm.clearCreateError(); composeOpen = true },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("new post") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.navigationBarsPadding().padding(Spacing.l),
                )
            }
        }
    }

    if (composeOpen) {
        NewPostDialog(
            creating = ui.creating,
            error = ui.createError,
            tags = ui.tags,
            mentions = ui.mentionCandidates,
            onMentionLookup = vm::loadMentionCandidates,
            avatarUrl = vm::mediaUrl,
            onDismiss = { if (!ui.creating) { composeOpen = false; vm.clearCreateError() } },
            onCreate = { postTitle, body, image, tagId ->
                vm.createPost(postTitle, body, image, tagId) { composeOpen = false }
            },
        )
    }

    if (tagDialogOpen) {
        CreateTagDialog(
            busy = ui.creatingTag,
            error = ui.tagError,
            onDismiss = { if (!ui.creatingTag) { tagDialogOpen = false; vm.clearTagError() } },
            onCreate = { name, markLabel -> vm.createTag(name, markLabel) { tagDialogOpen = false } },
        )
    }

    if (renameOpen) {
        RenameChannelDialog(
            current = liveTitle,
            busy = ui.renaming,
            onDismiss = { if (!ui.renaming) renameOpen = false },
            onRename = { name -> vm.renameChannel(name) { renameOpen = false } },
        )
    }
}

private suspend fun readPickedImage(context: android.content.Context, uri: android.net.Uri): PickedImage? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val type = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            var name = "image"
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i) ?: name
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            if (bytes.size > 25 * 1024 * 1024) return@runCatching null
            PickedImage(bytes, name.take(128), type.take(64))
        }.getOrNull()
    }

@Composable
private fun ForumAttachmentImage(attachment: app.pigeonsms.network.AttachmentDto?, avatarUrl: (String?) -> String?) {
    if (attachment == null || attachment.type?.startsWith("image/") != true) return
    val url = avatarUrl(attachment.key) ?: return
    coil.compose.AsyncImage(
        model = url,
        contentDescription = attachment.name ?: "image attachment",
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier
            .padding(top = Spacing.s)
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun PickedImagePreview(image: PickedImage, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        coil.compose.AsyncImage(
            model = image.bytes,
            contentDescription = "selected image",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(Corners.chip).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Text(
            image.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Spacing.s),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Outlined.Close, "remove image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RenameChannelDialog(current: String, busy: Boolean, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val galaxy = LocalUiSkin.current == UiSkin.Galaxy
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = if (galaxy) NovaCorners.card else AlertDialogDefaults.shape,
        title = { Text("rename channel") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("channel name") },
                enabled = !busy,
                singleLine = true,
                shape = if (galaxy) NovaCorners.input else Corners.input,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onRename(name) },
                enabled = !busy && name.isNotBlank() && name.trim() != current,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("saving...", Modifier.padding(start = Spacing.s))
                } else {
                    Text("rename")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("cancel") } },
    )
}

@Composable
private fun ForumHeader(title: String, subtitle: String, onBack: () -> Unit, onRename: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.s, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface)
        }
        Icon(
            Icons.Outlined.Forum,
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(start = Spacing.s)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRename != null) ForumOverflowMenu(onRename)
    }
}

@Composable
private fun ForumOverflowMenu(onRename: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, "channel options", tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("rename channel") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { open = false; onRename() },
            )
        }
    }
}

@Composable
private fun NovaForumHeader(title: String, subtitle: String, onBack: () -> Unit, onRename: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovaIconBadgeButton(onClick = onBack, size = 44.dp) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", Modifier.size(20.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.m)
                .heroAppear(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        if (onRename != null) {
            NovaIconBadgeButton(onClick = onRename, size = 44.dp) {
                Icon(Icons.Outlined.Edit, "rename channel", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PostList(
    ui: ForumViewModel.ForumUiState,
    avatarUrl: (String?) -> String?,
    onSort: (String) -> Unit,
    onRetry: () -> Unit,
    onOpen: (ForumPostDto) -> Unit,
    onOpenProfile: (String) -> Unit,
    onNewPost: () -> Unit,
    canDelete: (String) -> Boolean,
    onDelete: (String) -> Unit,
    onTagFilter: (String?) -> Unit,
    onLike: (String) -> Unit,
    onCreateTag: () -> Unit,
) {
    val skin = LocalUiSkin.current
    val galaxy = skin == UiSkin.Galaxy
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = if (galaxy) Spacing.s else Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            listOf("active" to "active", "recent" to "newest", "oldest" to "oldest").forEach { (key, label) ->
                if (galaxy) {
                    NovaTag(selected = ui.sort == key, onClick = { onSort(key) }) { Text(label) }
                } else {
                    FilterChip(
                        selected = ui.sort == key,
                        onClick = { onSort(key) },
                        label = { Text(label) },
                        shape = Corners.chip,
                    )
                }
            }
        }

        if (ui.tags.isNotEmpty() || ui.isOwner) {
            TagFilterRow(
                tags = ui.tags,
                selected = ui.tagFilter,
                galaxy = galaxy,
                canCreate = ui.isOwner,
                onSelect = onTagFilter,
                onCreateTag = onCreateTag,
            )
        }
        when {
            ui.loading && ui.posts.isEmpty() -> LoadingState("loading posts")
            ui.error != null && ui.posts.isEmpty() -> ErrorState(ui.error, onRetry)
            ui.posts.isEmpty() -> if (galaxy) {
                Empty(
                    "no posts yet",
                    "start the first conversation",
                    icon = Icons.Outlined.Forum,
                    action = { NovaPillButton(text = "start a post", onClick = onNewPost, modifier = Modifier.width(200.dp), leading = { Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)) }) },
                )
            } else {
                Empty("no posts yet", "start the first conversation")
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                itemsIndexed(ui.posts, key = { _, p -> p.id }) { index, post ->
                    val deletable = canDelete(post.author.id)
                    val onDeleteThis: (() -> Unit)? = if (deletable) { { onDelete(post.id) } } else null
                    // "new since last visit": post seq is past the read watermark we

                    // (baseline 0) doesn't light every post.
                    val isNew = ui.newSinceSeq > 0 && post.seq > ui.newSinceSeq
                    when (skin) {
                        UiSkin.Galaxy -> NovaPostCard(post, avatarUrl, isNew = isNew, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile, onDelete = onDeleteThis, onLike = { onLike(post.id) })
                        UiSkin.Nova -> ExpNovaPostCard(post, avatarUrl, isNew = isNew, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile, onDelete = onDeleteThis, onLike = { onLike(post.id) })
                        UiSkin.Classic -> PostCard(post, avatarUrl, isNew = isNew, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile, onDelete = onDeleteThis, onLike = { onLike(post.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun forumCard(): Modifier =
    Modifier.clip(Corners.card).background(MaterialTheme.colorScheme.surfaceContainer)

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun TagFilterRow(
    tags: List<ForumTagDto>,
    selected: String?,
    galaxy: Boolean,
    canCreate: Boolean,
    onSelect: (String?) -> Unit,
    onCreateTag: () -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = if (galaxy) Spacing.s else Spacing.xs),
        contentPadding = PaddingValues(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item(key = "__all") {
            if (galaxy) {
                NovaTag(selected = selected == null, onClick = { onSelect(null) }) { Text("all") }
            } else {
                FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("all") }, shape = Corners.chip)
            }
        }
        items(tags, key = { it.id }) { tag ->
            if (galaxy) {
                NovaTag(selected = selected == tag.id, onClick = { onSelect(tag.id) }) { Text(tag.name) }
            } else {
                FilterChip(
                    selected = selected == tag.id,
                    onClick = { onSelect(tag.id) },
                    label = { Text(tag.name) },
                    leadingIcon = if (tag.mark_label != null) {
                        { Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)) }
                    } else null,
                    shape = Corners.chip,
                )
            }
        }
        if (canCreate) {
            item(key = "__newtag") {
                FilterChip(
                    selected = false,
                    onClick = onCreateTag,
                    label = { Text("tag") },
                    leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) },
                    shape = Corners.chip,
                )
            }
        }
    }
}

@Composable
private fun ForumTagChip(tag: ForumTagDto, marked: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .clip(Corners.chip)
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = Spacing.s, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked && tag.mark_label != null) {
            Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = accent)
            Text(
                tag.mark_label!!,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.padding(start = 2.dp),
            )
        } else {
            Icon(Icons.Outlined.Label, null, Modifier.size(13.dp), tint = accent)
            Text(
                tag.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** "new" accent badge for posts unseen since the last visit. */
@Composable
private fun NewBadge() {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(Corners.chip).background(accent).padding(horizontal = Spacing.s, vertical = 1.dp),
    ) {
        Text(
            "new",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ForumLikeButton(liked: Boolean, count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(Corners.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            if (liked) "unlike" else "like",
            Modifier.size(18.dp),
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count > 0) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xxs),
            )
        }
    }
}

private fun forumMentionToken(value: TextFieldValue): Pair<Int, String>? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.end
    val s = value.text
    if (cursor > s.length) return null
    var i = cursor - 1
    while (i >= 0 && forumMentionWordChar(s[i])) i--
    if (i < 0 || s[i] != '@') return null
    if (i > 0 && forumMentionWordChar(s[i - 1])) return null
    return i to s.substring(i + 1, cursor)
}

private fun forumMentionWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

private fun forumInsertMention(value: TextFieldValue, username: String): TextFieldValue {
    val token = forumMentionToken(value) ?: return value
    val start = token.first
    val end = value.selection.end
    val insert = "@$username "
    val newText = value.text.substring(0, start) + insert + value.text.substring(end)
    val caret = start + insert.length
    return TextFieldValue(newText, TextRange(caret))
}

@Composable
private fun ForumMentionSuggestions(
    suggestions: List<ForumMentionCandidate>,
    avatarUrl: (String?) -> String?,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs)
            .heightIn(max = 48.dp * 4)
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(vertical = Spacing.xs),
    ) {
        items(suggestions, key = { it.id }) { candidate ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(candidate.username) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Avatar(name = candidate.username, model = avatarUrl(candidate.avatarKey), size = 28.dp)
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
private fun AuthorLine(author: ApiUser, timestamp: Long, avatarUrl: (String?) -> String?, onOpenProfile: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            name = author.display_name ?: author.username,
            model = avatarUrl(author.avatar_key),
            size = 20.dp,
            modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onOpenProfile(author.id) },
        )
        Text(
            author.display_name?.takeIf { it.isNotBlank() } ?: "@${author.username}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.xs).clickable { onOpenProfile(author.id) },
        )
        Text(
            " · ${smartTime(timestamp)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostCardBox(
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    content: @Composable (clickMod: Modifier) -> Unit,
) {

    // so the child never installs its own competing gesture.
    if (onDelete == null) {
        content(Modifier.clickableScale(pressedScale = 0.98f, onClick = onOpen))
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        content(
            Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("delete post") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menuOpen = false; onDelete!!() },
            )
        }
    }
}

@Composable
private fun PostCard(
    post: ForumPostDto,
    avatarUrl: (String?) -> String?,
    isNew: Boolean = false,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onLike: () -> Unit = {},
) = PostCardBox(onOpen, onDelete) { clickMod ->
    Column(
        modifier
            .fillMaxWidth()
            .then(forumCard())
            .then(clickMod)
            .padding(Spacing.l),
    ) {

        ForumCardBadges(post, isNew)
        Text(
            forumPostTitle(post.metadata),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (post.content.isNotBlank()) {
            Text(
                post.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        ForumAttachmentImage(post.attachment, avatarUrl)
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                AuthorLine(post.author, post.created_at, avatarUrl, onOpenProfile)
            }
            ForumLikeButton(post.liked, post.like_count, onLike)
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                null,
                Modifier.size(20.dp).padding(start = Spacing.xs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${post.reply_count}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

@Composable
private fun ForumCardBadges(post: ForumPostDto, isNew: Boolean) {
    if (!post.pinned && !isNew && post.tag == null) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (post.pinned) {
            Icon(Icons.Filled.PushPin, "pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (isNew) NewBadge()
        post.tag?.let { ForumTagChip(it, marked = post.marked) }
    }
}

@Composable
private fun ThreadView(
    thread: ForumViewModel.ThreadState,
    isOwner: Boolean,
    pinned: Boolean,
    marked: Boolean,
    markLabel: String?,
    canMark: Boolean,
    mentions: List<ForumMentionCandidate>,
    onMentionLookup: () -> Unit,
    avatarUrl: (String?) -> String?,
    onRetry: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onSend: (String, PickedImage?, () -> Unit) -> Unit,
    canDelete: (String) -> Boolean,
    onDelete: (String) -> Unit,
    onTogglePin: () -> Unit,
    onToggleMark: () -> Unit,
) {
    when {
        thread.loading -> { LoadingState("loading post"); return }
        thread.error != null -> { ErrorState(thread.error, onRetry); return }
    }
    val post = thread.post ?: return
    val skin = LocalUiSkin.current
    val galaxy = skin == UiSkin.Galaxy
    val nova = skin == UiSkin.Nova
    val listState = rememberLazyListState()
    var draft by rememberSaveable(post.id, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    LaunchedEffect(thread.replies.size) {
        if (thread.replies.isNotEmpty()) {
            // +1 for the root post header item
            listState.animateScrollToItem(thread.replies.size)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item(key = post.id) {
                Column {
                    if (galaxy) {
                        NovaThreadHero(post, thread.replies.size, avatarUrl, onOpenProfile)
                    } else if (nova) {
                        ExpNovaThreadHero(post, thread.replies.size, avatarUrl, onOpenProfile)
                    } else {
                        Column(Modifier.fillMaxWidth().then(forumCard()).padding(Spacing.l)) {
                            Text(
                                forumPostTitle(post.metadata),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Box(Modifier.padding(top = Spacing.xs)) {
                                AuthorLine(post.author, post.created_at, avatarUrl, onOpenProfile)
                            }
                            if (post.content.isNotBlank()) {
                                Text(
                                    post.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = Spacing.m),
                                )
                            }
                            ForumAttachmentImage(post.attachment, avatarUrl)
                            Text(
                                if (thread.replies.size == 1) "1 reply" else "${thread.replies.size} replies",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.m),
                            )
                        }
                    }

                    ThreadPostActions(
                        pinned = pinned,
                        marked = marked,
                        markLabel = markLabel,
                        canPin = isOwner,

                        canMark = canMark && markLabel != null,
                        canDelete = canDelete(post.author.id),
                        onTogglePin = onTogglePin,
                        onToggleMark = onToggleMark,
                        onDelete = { onDelete(post.id) },
                    )
                }
            }
            items(thread.replies, key = { it.id }) { reply ->
                val onDeleteReply: (() -> Unit)? =
                    if (!reply.deleted && canDelete(reply.author.id)) { { onDelete(reply.id) } } else null
                ReplyRowMenu(onDeleteReply) {
                    when (skin) {
                        UiSkin.Galaxy -> NovaReplyRow(reply, avatarUrl, onOpenProfile)
                        UiSkin.Nova -> ExpNovaReplyRow(reply, avatarUrl, onOpenProfile)
                        UiSkin.Classic -> ReplyRow(reply, avatarUrl, onOpenProfile)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = thread.sendError != null,
            enter = fadeIn(PigeonMotion.snappy()),
            exit = fadeOut(PigeonMotion.snappy()),
        ) {
            Text(
                thread.sendError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var replyImage by remember(post.id) { mutableStateOf<PickedImage?>(null) }
        val replyPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { replyImage = readPickedImage(context, uri) }
        }
        val armed = (draft.text.isNotBlank() || replyImage != null) && !thread.sending
        val submit: () -> Unit = {
            if (armed) {
                val img = replyImage
                onSend(draft.text, img) { draft = androidx.compose.ui.text.input.TextFieldValue(""); replyImage = null }
            }
        }
        // @mention autocomplete: fetch candidates lazily the first time an "@" appears,
        // and show the filtered panel above the composer.
        val mentionToken = remember(draft) { forumMentionToken(draft) }
        LaunchedEffect(mentionToken != null) { if (mentionToken != null) onMentionLookup() }
        val mentionSuggestions = remember(mentionToken, mentions) {
            val query = mentionToken?.second?.lowercase() ?: return@remember emptyList()
            mentions.filter { it.username.lowercase().startsWith(query) || (it.displayName?.lowercase()?.contains(query) == true) }
                .sortedByDescending { it.username.lowercase().startsWith(query) }
                .take(6)
        }
        if (mentionSuggestions.isNotEmpty()) {
            ForumMentionSuggestions(
                suggestions = mentionSuggestions,
                avatarUrl = avatarUrl,
                onPick = { username -> draft = forumInsertMention(draft, username) },
            )
        }

        replyImage?.let { img ->
            PickedImagePreview(
                image = img,
                onClear = { replyImage = null },
            )
        }
        val composerMod = if (galaxy) {
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.m, vertical = Spacing.s)
                .novaElevation(NovaCorners.input, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary, accented = armed)
                .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xxs, bottom = Spacing.xxs)
        } else {
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.m, vertical = Spacing.s)
        }
        Row(
            composerMod,
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = { replyPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !thread.sending,
            ) {
                Icon(Icons.Outlined.Image, "attach image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.text.length <= 4000) draft = it },
                placeholder = { Text("reply to this post") },
                enabled = !thread.sending,
                shape = if (galaxy) NovaCorners.input else Corners.input,
                maxLines = 4,
                colors = if (galaxy) OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ) else OutlinedTextFieldDefaults.colors(),
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            if (galaxy) {

                val sendShape = CircleShape
                Box(
                    Modifier
                        .padding(start = Spacing.xs, bottom = Spacing.xs)
                        .size(44.dp)
                        .then(
                            if (armed) Modifier
                                .novaElevation(sendShape, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary, accented = true, glow = true)
                                .background(Brush.linearGradient(NovaGradients.cta(MaterialTheme.colorScheme.primary)), sendShape)
                            else Modifier
                                .clip(sendShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                        .clickableScale(pressedScale = 0.9f, onClick = submit),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thread.sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            "send reply",
                            Modifier.size(20.dp),
                            tint = if (armed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = submit,
                    enabled = armed,
                    modifier = Modifier.padding(start = Spacing.xs),
                ) {
                    if (thread.sending) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            "send reply",
                            tint = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadPostActions(
    pinned: Boolean,
    marked: Boolean,
    markLabel: String?,
    canPin: Boolean,
    canMark: Boolean,
    canDelete: Boolean,
    onTogglePin: () -> Unit,
    onToggleMark: () -> Unit,
    onDelete: () -> Unit,
) {
    if (!canPin && !canMark && !canDelete) return
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.xs, start = Spacing.s, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (canPin) {
            TextButton(onClick = onTogglePin) {
                Icon(
                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    null,
                    Modifier.size(18.dp),
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (pinned) "unpin" else "pin",
                    Modifier.padding(start = Spacing.xs),
                    color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (canMark && markLabel != null) {
            TextButton(onClick = onToggleMark) {
                Icon(
                    Icons.Outlined.Check,
                    null,
                    Modifier.size(18.dp),
                    tint = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (marked) "✓ $markLabel" else "mark as $markLabel",
                    Modifier.padding(start = Spacing.xs),
                    color = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (canDelete) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text("delete", Modifier.padding(start = Spacing.xs), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyRowMenu(onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    if (onDelete == null) { content(); return }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.combinedClickable(onClick = {}, onLongClick = { open = true })) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("delete reply") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun ReplyRow(reply: MessageDto, avatarUrl: (String?) -> String?, onOpenProfile: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(forumCard())
            .padding(Spacing.m),
    ) {
        AuthorLine(reply.author, reply.created_at, avatarUrl, onOpenProfile)
        Text(
            if (reply.deleted) "deleted reply" else reply.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (reply.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (!reply.deleted) ForumAttachmentImage(reply.attachment, avatarUrl)
    }
}

@Composable
private fun NewPostDialog(
    creating: Boolean,
    error: String?,
    tags: List<ForumTagDto>,
    mentions: List<ForumMentionCandidate>,
    onMentionLookup: () -> Unit,
    avatarUrl: (String?) -> String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String, image: PickedImage?, tagId: String?) -> Unit,
) {
    val galaxy = LocalUiSkin.current == UiSkin.Galaxy
    val inputShape = if (galaxy) NovaCorners.input else Corners.input
    var postTitle by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var image by remember { mutableStateOf<PickedImage?>(null) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { image = readPickedImage(context, uri) }
    }
    // @mention autocomplete for the body field.
    val mentionToken = remember(body) { forumMentionToken(body) }
    LaunchedEffect(mentionToken != null) { if (mentionToken != null) onMentionLookup() }
    val mentionSuggestions = remember(mentionToken, mentions) {
        val query = mentionToken?.second?.lowercase() ?: return@remember emptyList()
        mentions.filter { it.username.lowercase().startsWith(query) || (it.displayName?.lowercase()?.contains(query) == true) }
            .sortedByDescending { it.username.lowercase().startsWith(query) }
            .take(6)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = if (galaxy) NovaCorners.card else AlertDialogDefaults.shape,
        title = { Text("new post") },
        text = {
            Column {
                OutlinedTextField(
                    value = postTitle,
                    onValueChange = { postTitle = it.take(160) },
                    label = { Text("title") },
                    supportingText = { Text("${postTitle.length}/160") },
                    enabled = !creating,
                    singleLine = true,
                    shape = inputShape,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.text.length <= 4000) body = it },

                    label = { Text("description (optional)") },
                    enabled = !creating,
                    shape = inputShape,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s).heightIn(min = 96.dp),
                )
                if (mentionSuggestions.isNotEmpty()) {
                    ForumMentionSuggestions(
                        suggestions = mentionSuggestions,
                        avatarUrl = avatarUrl,
                        onPick = { username -> body = forumInsertMention(body, username) },
                    )
                }

                if (tags.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        items(tags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = selectedTag == tag.id,
                                onClick = { selectedTag = if (selectedTag == tag.id) null else tag.id },
                                label = { Text(tag.name) },
                                enabled = !creating,
                                leadingIcon = if (tag.mark_label != null) {
                                    { Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)) }
                                } else null,
                                shape = Corners.chip,
                            )
                        }
                    }
                }
                if (image == null) {
                    TextButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !creating,
                        modifier = Modifier.padding(top = Spacing.xs),
                    ) {
                        Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                        Text("add image", Modifier.padding(start = Spacing.xs))
                    }
                } else {
                    PickedImagePreview(image = image!!, onClear = { image = null })
                }
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(PigeonMotion.snappy()),
                    exit = fadeOut(PigeonMotion.snappy()),
                ) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
        },
        confirmButton = {

            val canPost = !creating && postTitle.isNotBlank()
            if (galaxy) {
                NovaPillButton(
                    text = if (creating) "posting..." else "post",
                    onClick = { if (canPost) onCreate(postTitle, body.text, image, selectedTag) },
                    armed = canPost,
                    height = 48.dp,
                    modifier = Modifier.width(150.dp),
                    leading = if (creating) {
                        { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null,
                )
            } else {
                Button(
                    onClick = { onCreate(postTitle, body.text, image, selectedTag) },
                    enabled = canPost,
                ) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("posting...", Modifier.padding(start = Spacing.s))
                    } else {
                        Text("post")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("cancel") } },
    )
}

@Composable
private fun CreateTagDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, markLabel: String?) -> Unit,
) {
    val galaxy = LocalUiSkin.current == UiSkin.Galaxy
    val inputShape = if (galaxy) NovaCorners.input else Corners.input
    var name by rememberSaveable { mutableStateOf("") }
    var markable by rememberSaveable { mutableStateOf(false) }
    var markLabel by rememberSaveable { mutableStateOf("") }
    val canCreate = !busy && name.isNotBlank() && (!markable || markLabel.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = if (galaxy) NovaCorners.card else AlertDialogDefaults.shape,
        title = { Text("new tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("tag name") },
                    enabled = !busy,
                    singleLine = true,
                    shape = inputShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("can be marked", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "posts with this tag get a \"mark as …\" button",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = markable, onCheckedChange = { markable = it }, enabled = !busy)
                }
                AnimatedVisibility(visible = markable) {
                    OutlinedTextField(
                        value = markLabel,
                        onValueChange = { markLabel = it.take(40) },
                        label = { Text("mark label (e.g. completed)") },
                        enabled = !busy,
                        singleLine = true,
                        shape = inputShape,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    )
                }
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(PigeonMotion.snappy()),
                    exit = fadeOut(PigeonMotion.snappy()),
                ) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, if (markable) markLabel else null) },
                enabled = canCreate,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("saving...", Modifier.padding(start = Spacing.s))
                } else {
                    Text("create")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("cancel") } },
    )
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun NovaPostCard(
    post: ForumPostDto,
    avatarUrl: (String?) -> String?,
    isNew: Boolean = false,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onLike: () -> Unit = {},
) = PostCardBox(onOpen, onDelete) { clickMod ->
    val accent = MaterialTheme.colorScheme.primary
    val cyan = MaterialTheme.colorScheme.secondary

    val hot = post.reply_count > 0 || isNew
    Row(
        modifier
            .fillMaxWidth()
            .novaElevation(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, accent, accented = hot)
            .then(clickMod)
            .padding(Spacing.l),
    ) {

        Column(
            Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(NovaGradients.coverMesh(accent)))
                .novaHalo(cyan, alpha = if (hot) 0.20f else 0.10f)
                .padding(vertical = Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            NovaAnimatedCount(
                count = post.reply_count,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        Column(Modifier.weight(1f).padding(start = Spacing.m)) {
            ForumCardBadges(post, isNew)

            Text(
                forumPostTitle(post.metadata),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            ForumAttachmentImage(post.attachment, avatarUrl)

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name = post.author.display_name ?: post.author.username,
                    model = avatarUrl(post.author.avatar_key),
                    size = 22.dp,
                    modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(post.author.id) },
                )
                Text(
                    post.author.display_name?.takeIf { it.isNotBlank() } ?: "@${post.author.username}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = Spacing.xs).clickable { onOpenProfile(post.author.id) },
                )
                Spacer(Modifier.weight(1f))
                ForumLikeButton(post.liked, post.like_count, onLike)
                Icon(Icons.Outlined.Schedule, null, Modifier.padding(start = Spacing.xs).size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    " ${smartTime(if (post.last_activity_at > post.created_at) post.last_activity_at else post.created_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NovaThreadHero(
    post: app.pigeonsms.network.MessageDto,
    replyCount: Int,
    avatarUrl: (String?) -> String?,
    onOpenProfile: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .heroAppear()
            .novaElevation(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, accent, accented = true, glow = true)
            .clip(NovaCorners.card),
    ) {

        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(NovaGradients.heroWash(accent)))
                .padding(Spacing.l),
        ) {
            Text(
                forumPostTitle(post.metadata),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = post.author.display_name ?: post.author.username,
                    model = avatarUrl(post.author.avatar_key),
                    size = 24.dp,
                    modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(post.author.id) },
                )
                Text(
                    post.author.display_name?.takeIf { it.isNotBlank() } ?: "@${post.author.username}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Spacing.s).clickable { onOpenProfile(post.author.id) },
                )
                Icon(Icons.Outlined.Schedule, null, Modifier.padding(start = Spacing.s).size(13.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(
                    " ${smartTime(post.created_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (post.content.isNotBlank()) {
            Text(
                post.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
        }
        Box(Modifier.padding(horizontal = Spacing.l)) {
            ForumAttachmentImage(post.attachment, avatarUrl)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .novaElevation(CircleShape, MaterialTheme.colorScheme.surfaceContainerHigh, accent, accented = true, glow = true, elevation = NovaDepth.raisedElevation)
                    .background(Brush.horizontalGradient(NovaGradients.cta(accent)), CircleShape)
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovaAnimatedCount(
                    count = replyCount,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    if (replyCount == 1) " reply" else " replies",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun NovaReplyRow(reply: MessageDto, avatarUrl: (String?) -> String?, onOpenProfile: (String) -> Unit) {

    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.s, end = Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(
            name = reply.author.display_name ?: reply.author.username,
            model = avatarUrl(reply.author.avatar_key),
            size = 32.dp,
            modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(reply.author.id) },
        )
        val bubbleShape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
        Column(
            Modifier
                .padding(start = Spacing.s)
                .weight(1f)
                .novaSurface(bubbleShape, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary)
                .padding(Spacing.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reply.author.display_name?.takeIf { it.isNotBlank() } ?: "@${reply.author.username}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenProfile(reply.author.id) },
                )
                Text(
                    " · ${smartTime(reply.created_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                if (reply.deleted) "deleted reply" else reply.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (reply.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
            if (!reply.deleted) ForumAttachmentImage(reply.attachment, avatarUrl)
        }
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun ExpNovaPostCard(
    post: ForumPostDto,
    avatarUrl: (String?) -> String?,
    isNew: Boolean = false,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onLike: () -> Unit = {},
) = PostCardBox(onOpen, onDelete) { clickMod ->
    Row(
        modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(clickMod)
            .padding(Spacing.l),
    ) {

        Column(
            Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(vertical = Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "${post.reply_count}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        Column(Modifier.weight(1f).padding(start = Spacing.m)) {
            ForumCardBadges(post, isNew)

            Text(
                forumPostTitle(post.metadata),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            ForumAttachmentImage(post.attachment, avatarUrl)

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name = post.author.display_name ?: post.author.username,
                    model = avatarUrl(post.author.avatar_key),
                    size = 22.dp,
                    modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(post.author.id) },
                )
                Text(
                    post.author.display_name?.takeIf { it.isNotBlank() } ?: "@${post.author.username}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(start = Spacing.xs).clickable { onOpenProfile(post.author.id) },
                )
                Spacer(Modifier.weight(1f))
                ForumLikeButton(post.liked, post.like_count, onLike)
                Icon(Icons.Outlined.Schedule, null, Modifier.padding(start = Spacing.xs).size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    " ${smartTime(if (post.last_activity_at > post.created_at) post.last_activity_at else post.created_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpNovaThreadHero(
    post: MessageDto,
    replyCount: Int,
    avatarUrl: (String?) -> String?,
    onOpenProfile: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(Spacing.l),
        ) {
            Text(
                forumPostTitle(post.metadata),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = post.author.display_name ?: post.author.username,
                    model = avatarUrl(post.author.avatar_key),
                    size = 24.dp,
                    modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(post.author.id) },
                )
                Text(
                    post.author.display_name?.takeIf { it.isNotBlank() } ?: "@${post.author.username}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Spacing.s).clickable { onOpenProfile(post.author.id) },
                )
                Text(
                    " · ${smartTime(post.created_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (post.content.isNotBlank()) {
            Text(
                post.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
        }
        Box(Modifier.padding(horizontal = Spacing.l)) {
            ForumAttachmentImage(post.attachment, avatarUrl)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
            ) {
                Text(
                    if (replyCount == 1) "1 reply" else "$replyCount replies",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun ExpNovaReplyRow(reply: MessageDto, avatarUrl: (String?) -> String?, onOpenProfile: (String) -> Unit) {

    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.s, end = Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(
            name = reply.author.display_name ?: reply.author.username,
            model = avatarUrl(reply.author.avatar_key),
            size = 32.dp,
            modifier = Modifier.clip(CircleShape).clickable { onOpenProfile(reply.author.id) },
        )
        Column(
            Modifier
                .padding(start = Spacing.s)
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(Spacing.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reply.author.display_name?.takeIf { it.isNotBlank() } ?: "@${reply.author.username}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenProfile(reply.author.id) },
                )
                Text(
                    " · ${smartTime(reply.created_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (reply.deleted) "deleted reply" else reply.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (reply.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
            if (!reply.deleted) ForumAttachmentImage(reply.attachment, avatarUrl)
        }
    }
}
