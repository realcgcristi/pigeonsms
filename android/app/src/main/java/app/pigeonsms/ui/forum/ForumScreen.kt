package app.pigeonsms.ui.forum

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.PigeonMotion
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.ApiUser
import app.pigeonsms.network.ForumPostDto
import app.pigeonsms.network.MessageDto
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Avatar
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.LoadingState
import app.pigeonsms.ui.util.clickableScale
import app.pigeonsms.ui.util.smartTime

@Composable
fun ForumScreen(
    channelId: String,
    title: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val vm: ForumViewModel = pigeonVm(key = "forum-$channelId") { c, _ ->
        ForumViewModel(c.api, c.gateway, channelId)
    }
    val ui by vm.ui.collectAsState()
    var composeOpen by rememberSaveable(channelId) { mutableStateOf(false) }

    BackHandler(enabled = ui.openPostId != null) { vm.closePost() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ForumHeader(
                title = title,
                subtitle = if (ui.openPostId != null) {
                    ui.thread.post?.let { forumPostTitle(it.metadata) } ?: "post"
                } else {
                    "forum"
                },
                onBack = { if (ui.openPostId != null) vm.closePost() else onBack() },
            )

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
                    )
                } else {
                    ThreadView(
                        thread = ui.thread,
                        avatarUrl = vm::mediaUrl,
                        onRetry = { vm.openPost(openPostId) },
                        onOpenProfile = onOpenProfile,
                        onSend = { text, done -> vm.sendReply(text, done) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = ui.openPostId == null,
            enter = fadeIn(PigeonMotion.snappy()),
            exit = fadeOut(PigeonMotion.snappy()),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
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

    if (composeOpen) {
        NewPostDialog(
            creating = ui.creating,
            error = ui.createError,
            onDismiss = { if (!ui.creating) { composeOpen = false; vm.clearCreateError() } },
            onCreate = { postTitle, body -> vm.createPost(postTitle, body) { composeOpen = false } },
        )
    }
}

@Composable
private fun ForumHeader(title: String, subtitle: String, onBack: () -> Unit) {
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
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            listOf("active" to "active", "recent" to "newest", "oldest" to "oldest").forEach { (key, label) ->
                FilterChip(
                    selected = ui.sort == key,
                    onClick = { onSort(key) },
                    label = { Text(label) },
                    shape = Corners.chip,
                )
            }
        }
        when {
            ui.loading && ui.posts.isEmpty() -> LoadingState("loading posts")
            ui.error != null && ui.posts.isEmpty() -> ErrorState(ui.error, onRetry)
            ui.posts.isEmpty() -> Empty("no posts yet", "start the first conversation")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                itemsIndexed(ui.posts, key = { _, p -> p.id }) { index, post ->
                    PostCard(post, avatarUrl, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile)
                }
            }
        }
    }
}

@Composable
private fun forumCard(): Modifier =
    Modifier.clip(Corners.card).background(MaterialTheme.colorScheme.surfaceContainer)

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

@Composable
private fun PostCard(
    post: ForumPostDto,
    avatarUrl: (String?) -> String?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(forumCard())
            .clickableScale(pressedScale = 0.98f, onClick = onOpen)
            .padding(Spacing.l),
    ) {
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
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                AuthorLine(post.author, post.created_at, avatarUrl, onOpenProfile)
            }
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${post.reply_count}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs),
            )
            if (post.last_activity_at > post.created_at) {
                Text(
                    " · ${smartTime(post.last_activity_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThreadView(
    thread: ForumViewModel.ThreadState,
    avatarUrl: (String?) -> String?,
    onRetry: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onSend: (String, () -> Unit) -> Unit,
) {
    when {
        thread.loading -> { LoadingState("loading post"); return }
        thread.error != null -> { ErrorState(thread.error, onRetry); return }
    }
    val post = thread.post ?: return
    val listState = rememberLazyListState()
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
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
                    Text(
                        if (thread.replies.size == 1) "1 reply" else "${thread.replies.size} replies",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.m),
                    )
                }
            }
            items(thread.replies, key = { it.id }) { reply ->
                ReplyRow(reply, avatarUrl, onOpenProfile)
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
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(4000) },
                placeholder = { Text("reply to this post") },
                enabled = !thread.sending,
                shape = Corners.input,
                maxLines = 4,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            IconButton(
                onClick = { onSend(draft) { draft = "" } },
                enabled = draft.isNotBlank() && !thread.sending,
                modifier = Modifier.padding(start = Spacing.xs),
            ) {
                if (thread.sending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        "send reply",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    }
}

@Composable
private fun NewPostDialog(
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String) -> Unit,
) {
    var postTitle by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    shape = Corners.input,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(4000) },
                    label = { Text("what's on your mind?") },
                    enabled = !creating,
                    shape = Corners.input,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s).heightIn(min = 96.dp),
                )
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
                onClick = { onCreate(postTitle, body) },
                enabled = !creating && postTitle.isNotBlank() && body.isNotBlank(),
            ) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("posting...", Modifier.padding(start = Spacing.s))
                } else {
                    Text("post")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("cancel") } },
    )
}
