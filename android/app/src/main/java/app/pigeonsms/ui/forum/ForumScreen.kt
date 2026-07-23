package app.pigeonsms.ui.forum

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

    val galaxy = LocalUiSkin.current == UiSkin.Galaxy
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val headerSubtitle = if (ui.openPostId != null) {
                ui.thread.post?.let { forumPostTitle(it.metadata) } ?: "post"
            } else {
                val n = ui.posts.size
                if (n == 0) "forum" else if (n == 1) "1 post" else "$n posts"
            }
            if (galaxy) {
                NovaForumHeader(
                    title = title,
                    subtitle = headerSubtitle,
                    onBack = { if (ui.openPostId != null) vm.closePost() else onBack() },
                )
            } else {
                ForumHeader(
                    title = title,
                    subtitle = if (ui.openPostId != null) {
                        ui.thread.post?.let { forumPostTitle(it.metadata) } ?: "post"
                    } else {
                        "forum"
                    },
                    onBack = { if (ui.openPostId != null) vm.closePost() else onBack() },
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
private fun NovaForumHeader(title: String, subtitle: String, onBack: () -> Unit) {
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
                    when (skin) {
                        UiSkin.Galaxy -> NovaPostCard(post, avatarUrl, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile)
                        UiSkin.Nova -> ExpNovaPostCard(post, avatarUrl, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile)
                        UiSkin.Classic -> PostCard(post, avatarUrl, modifier = Modifier.itemAppear(index), onOpen = { onOpen(post) }, onOpenProfile = onOpenProfile)
                    }
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
    val skin = LocalUiSkin.current
    val galaxy = skin == UiSkin.Galaxy
    val nova = skin == UiSkin.Nova
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
                        Text(
                            if (thread.replies.size == 1) "1 reply" else "${thread.replies.size} replies",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.m),
                        )
                    }
                }
            }
            items(thread.replies, key = { it.id }) { reply ->
                when (skin) {
                    UiSkin.Galaxy -> NovaReplyRow(reply, avatarUrl, onOpenProfile)
                    UiSkin.Nova -> ExpNovaReplyRow(reply, avatarUrl, onOpenProfile)
                    UiSkin.Classic -> ReplyRow(reply, avatarUrl, onOpenProfile)
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
        val armed = draft.isNotBlank() && !thread.sending
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
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(4000) },
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
                        .clickableScale(pressedScale = 0.9f, onClick = { if (armed) onSend(draft) { draft = "" } }),
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
    val galaxy = LocalUiSkin.current == UiSkin.Galaxy
    val inputShape = if (galaxy) NovaCorners.input else Corners.input
    var postTitle by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
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
                    onValueChange = { body = it.take(4000) },
                    label = { Text("what's on your mind?") },
                    enabled = !creating,
                    shape = inputShape,
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
            val canPost = !creating && postTitle.isNotBlank() && body.isNotBlank()
            if (galaxy) {
                NovaPillButton(
                    text = if (creating) "posting..." else "post",
                    onClick = { if (canPost) onCreate(postTitle, body) },
                    armed = canPost,
                    height = 48.dp,
                    modifier = Modifier.width(150.dp),
                    leading = if (creating) {
                        { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null,
                )
            } else {
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
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("cancel") } },
    )
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun NovaPostCard(
    post: ForumPostDto,
    avatarUrl: (String?) -> String?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val cyan = MaterialTheme.colorScheme.secondary

    // read as "hot" without an intrusive full outline.
    val hot = post.reply_count > 0
    Row(
        modifier
            .fillMaxWidth()
            .novaElevation(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, accent, accented = hot)
            .clickableScale(pressedScale = 0.98f, onClick = onOpen)
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
                Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
    }
}

// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------

@Composable
private fun ExpNovaPostCard(
    post: ForumPostDto,
    avatarUrl: (String?) -> String?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickableScale(pressedScale = 0.98f, onClick = onOpen)
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
                Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
    }
}
