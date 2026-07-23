package app.pigeonsms.ui.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.network.AttachmentDto
import app.pigeonsms.network.ForumLikeEventDto
import app.pigeonsms.network.ForumPostDto
import app.pigeonsms.network.ForumTagDto
import app.pigeonsms.network.Gateway
import app.pigeonsms.network.MessageDto
import app.pigeonsms.network.PigeonApi
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PickedImage(val bytes: ByteArray, val name: String, val type: String)

data class ForumMentionCandidate(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val avatarKey: String? = null,
)

fun forumPostTitle(metadata: JsonElement?): String = runCatching {
    metadata?.jsonObject?.get("title")?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "untitled post"

class ForumViewModel(
    private val api: PigeonApi,
    private val social: app.pigeonsms.data.SocialRepository,
    gateway: Gateway,
    private val channelId: String,
    initialTitle: String = "",
) : ViewModel() {

    data class ThreadState(
        val post: MessageDto? = null,
        val replies: List<MessageDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val sending: Boolean = false,
        val sendError: String? = null,
    )

    data class ForumUiState(
        val posts: List<ForumPostDto> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val sort: String = "active",
        val creating: Boolean = false,
        val createError: String? = null,

        val openPostId: String? = null,
        val thread: ThreadState = ThreadState(),

        val title: String = "",

        val isOwner: Boolean = false,

        val selfId: String? = null,

        val spaceId: String? = null,
        val renaming: Boolean = false,

        val openPostPinned: Boolean = false,

        val openPostMarked: Boolean = false,

        val openPostTag: ForumTagDto? = null,

        val openPostIsOp: Boolean = false,

        val tags: List<ForumTagDto> = emptyList(),

        val tagFilter: String? = null,
        val creatingTag: Boolean = false,
        val tagError: String? = null,
        /** @mention candidates for the composers (space members), lazily loaded. */
        val mentionCandidates: List<ForumMentionCandidate> = emptyList(),

        val newSinceSeq: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _ui = MutableStateFlow(ForumUiState(title = initialTitle))
    val ui: StateFlow<ForumUiState> = _ui
    private var listJob: Job? = null

    private var newBaselineCaptured = false
    private var mentionsLoaded = false
    private var mentionJob: Job? = null

    init {
        refresh()
        resolveRole()
        loadTags()
        viewModelScope.launch {
            gateway.events.collect { ev ->
                if (ev.t == "message.delete") {

                    val data = ev.d.jsonObject
                    val id = data["id"]?.jsonPrimitive?.content ?: return@collect
                    val channel = data["channel_id"]?.jsonPrimitive?.content
                    if (channel != null && channel != channelId) return@collect
                    val state = _ui.value
                    val touches = state.posts.any { it.id == id } ||
                        state.openPostId == id ||
                        state.thread.replies.any { it.id == id }
                    if (!touches) return@collect
                    _ui.update { s ->
                        s.copy(
                            posts = s.posts.filterNot { it.id == id },

                            openPostId = s.openPostId?.takeUnless { it == id },
                            thread = if (s.openPostId == id) ThreadState()
                            else s.thread.copy(replies = s.thread.replies.filterNot { it.id == id }),
                        )
                    }
                    refresh(silent = true) // reply counts + activity ordering
                    return@collect
                }

                if (ev.t == "forum.like") {
                    val like = runCatching { json.decodeFromJsonElement(ForumLikeEventDto.serializer(), ev.d) }.getOrNull()
                        ?: return@collect
                    if (like.channel_id != channelId) return@collect
                    _ui.update { s ->
                        s.copy(posts = s.posts.map {
                            if (it.id == like.message_id) it.copy(like_count = like.like_count) else it
                        })
                    }
                    return@collect
                }

                // and the open-thread pin/mark indicators.
                if (ev.t == "forum.post.update") {
                    val dto = runCatching { json.decodeFromJsonElement(ForumPostDto.serializer(), ev.d) }.getOrNull()
                        ?: return@collect
                    if (dto.channel_id != channelId) return@collect
                    _ui.update { s ->
                        s.copy(
                            posts = s.posts.map { if (it.id == dto.id) dto else it },
                            openPostPinned = if (s.openPostId == dto.id) dto.pinned else s.openPostPinned,
                            openPostMarked = if (s.openPostId == dto.id) dto.marked else s.openPostMarked,
                            openPostTag = if (s.openPostId == dto.id) dto.tag else s.openPostTag,
                        )
                    }
                    return@collect
                }
                if (ev.t != "forum.post" && ev.t != "forum.reply") return@collect
                val dto = runCatching { json.decodeFromJsonElement(MessageDto.serializer(), ev.d) }.getOrNull()
                    ?: return@collect
                if (dto.channel_id != channelId) return@collect
                when (ev.t) {

                    "forum.post" -> refresh(silent = true)
                    "forum.reply" -> {
                        val open = _ui.value.openPostId
                        if (open != null && dto.thread_id == open) {
                            _ui.update { state ->
                                if (state.thread.replies.any { it.id == dto.id }) state
                                else state.copy(thread = state.thread.copy(replies = state.thread.replies + dto))
                            }
                        }
                        refresh(silent = true) // reply counts + activity ordering
                    }
                }
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            if (!silent) _ui.update { it.copy(loading = true, error = null) }
            runCatching { api.forumPosts(channelId, _ui.value.sort, _ui.value.tagFilter) }
                .onSuccess { posts ->

                    // it, so posts newer than the user's last visit stay highlighted for
                    // the whole session on this channel.
                    if (!newBaselineCaptured) {
                        newBaselineCaptured = true
                        val baseline = runCatching { api.channelLastSeq(channelId) }.getOrDefault(0L)
                        _ui.update { it.copy(newSinceSeq = baseline) }
                    }
                    _ui.update { it.copy(posts = posts, loading = false, error = null) }

                    // so marking read at the max post seq leaves the unread badge
                    // stuck — probe the channel's own last_seq and take the max.
                    val seen = maxOf(api.channelLastSeq(channelId), posts.maxOfOrNull { it.seq } ?: 0L)
                    if (seen > 0) api.markRead(channelId, seen)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _ui.update {
                        it.copy(loading = false, error = if (silent) it.error else userMessage(error, "couldn't load posts"))
                    }
                }
        }
    }

    fun setSort(sort: String) {
        if (sort == _ui.value.sort) return
        _ui.update { it.copy(sort = sort) }
        refresh()
    }

    fun clearCreateError() = _ui.update { it.copy(createError = null) }

    fun createPost(title: String, content: String, image: PickedImage? = null, tag: String? = null, onDone: () -> Unit) {
        val cleanTitle = title.trim().take(160)
        val cleanContent = content.trim().take(4000)
        when {
            _ui.value.creating -> return

            cleanTitle.isEmpty() -> { _ui.update { it.copy(createError = "give your post a title") }; return }
        }
        _ui.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            runCatching {
                val attachment = image?.let { social.uploadFile(it.bytes, it.name, it.type) }
                api.createForumPost(channelId, cleanTitle, cleanContent, UUID.randomUUID().toString(), attachment, tag)
            }
                .onSuccess { message ->
                    _ui.update { it.copy(creating = false) }
                    refresh(silent = true)
                    onDone()
                    openPost(message.id)
                }
                .onFailure { error ->
                    _ui.update { it.copy(creating = false, createError = userMessage(error, "couldn't create the post")) }
                }
        }
    }

    fun openPost(postId: String) {

        // thread's indicators are correct immediately (the thread endpoint returns a

        val row = _ui.value.posts.firstOrNull { it.id == postId }
        _ui.update {
            it.copy(
                openPostId = postId,
                thread = ThreadState(loading = true),
                openPostPinned = row?.pinned ?: false,
                openPostMarked = row?.marked ?: false,
                openPostTag = row?.tag,
                openPostIsOp = row?.author?.id != null && row.author.id == it.selfId,
            )
        }

        if (row == null) viewModelScope.launch {
            val pinned = runCatching { api.pins(channelId).any { it.id == postId } }.getOrDefault(false)
            _ui.update { if (it.openPostId == postId) it.copy(openPostPinned = pinned) else it }
        }
        viewModelScope.launch {
            runCatching { api.forumThread(channelId, postId) }
                .onSuccess { thread ->

                    _ui.update { state ->
                        if (state.openPostId != postId) state
                        else state.copy(
                            thread = ThreadState(post = thread.post, replies = thread.replies),
                            openPostIsOp = state.selfId != null && thread.post.author.id == state.selfId,
                        )
                    }
                    thread.replies.maxOfOrNull { it.seq }?.let { api.markRead(channelId, it) }
                }
                .onFailure { error ->
                    _ui.update { state ->
                        if (state.openPostId != postId) state
                        else state.copy(thread = ThreadState(error = userMessage(error, "couldn't load this post")))
                    }
                }
        }
    }

    fun closePost() = _ui.update {
        it.copy(
            openPostId = null,
            thread = ThreadState(),
            openPostPinned = false,
            openPostMarked = false,
            openPostTag = null,
            openPostIsOp = false,
        )
    }

    fun sendReply(content: String, image: PickedImage? = null, onSent: () -> Unit) {
        val postId = _ui.value.openPostId ?: return
        val clean = content.trim().take(4000)
        if ((clean.isEmpty() && image == null) || _ui.value.thread.sending) return
        _ui.update { it.copy(thread = it.thread.copy(sending = true, sendError = null)) }
        viewModelScope.launch {
            runCatching {
                val attachment = image?.let { social.uploadFile(it.bytes, it.name, it.type) }
                api.createForumReply(channelId, postId, clean, UUID.randomUUID().toString(), attachment = attachment)
            }
                .onSuccess { message ->
                    _ui.update { state ->
                        if (state.openPostId != postId) state.copy(thread = state.thread.copy(sending = false))
                        else state.copy(
                            thread = state.thread.copy(
                                sending = false,

                                replies = if (state.thread.replies.any { it.id == message.id }) {
                                    state.thread.replies
                                } else {
                                    state.thread.replies + message
                                },
                            ),
                        )
                    }
                    onSent()
                    refresh(silent = true)
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(thread = it.thread.copy(sending = false, sendError = userMessage(error, "couldn't send the reply")))
                    }
                }
        }
    }

    private fun resolveRole() {
        viewModelScope.launch {
            val self = runCatching { api.me().id }.getOrNull()
            val space = runCatching { api.spaces() }.getOrNull()
                ?.firstOrNull { s -> s.channels.any { it.id == channelId } }
            _ui.update {
                it.copy(
                    selfId = self ?: it.selfId,
                    spaceId = space?.id ?: it.spaceId,
                    isOwner = space?.role == "owner" || (space != null && self != null && space.owner_id == self),
                )
            }
        }
    }

    fun canDelete(authorId: String): Boolean {
        val s = _ui.value
        return s.isOwner || (s.selfId != null && s.selfId == authorId)
    }

    fun renameChannel(name: String, onDone: () -> Unit) {
        val spaceId = _ui.value.spaceId ?: return

        val clean = name.trim().lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(32)
        if (clean.length < 2 || _ui.value.renaming) {
            _ui.update { it.copy(error = "channel name needs at least 2 letters or numbers") }
            return
        }
        _ui.update { it.copy(renaming = true) }
        viewModelScope.launch {
            runCatching { social.renameChannel(spaceId, channelId, clean) }
                .onSuccess {
                    _ui.update { it.copy(renaming = false, title = clean) }
                    onDone()
                }
                .onFailure { error ->
                    _ui.update { it.copy(renaming = false, error = userMessage(error, "couldn't rename channel")) }
                }
        }
    }

    fun deletePost(id: String) {
        viewModelScope.launch {
            runCatching { api.deleteMessage(id) }
                .onSuccess {
                    _ui.update { s ->
                        s.copy(
                            posts = s.posts.filterNot { it.id == id },
                            openPostId = s.openPostId?.takeUnless { it == id },
                            thread = if (s.openPostId == id) ThreadState()
                            else s.thread.copy(replies = s.thread.replies.filterNot { it.id == id }),
                        )
                    }
                    refresh(silent = true)
                }
                .onFailure { error ->
                    _ui.update { it.copy(error = userMessage(error, "couldn't delete")) }
                }
        }
    }

    fun togglePin(id: String) {
        val pinned = _ui.value.openPostPinned
        viewModelScope.launch {
            runCatching { if (pinned) api.unpin(id) else api.pin(id) }
                .onSuccess {
                    _ui.update { s ->
                        s.copy(
                            openPostPinned = if (s.openPostId == id) !pinned else s.openPostPinned,
                            posts = s.posts.map { if (it.id == id) it.copy(pinned = !pinned) else it },
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(error = userMessage(error, if (pinned) "couldn't unpin" else "couldn't pin")) }
                }
        }
    }

    // --- tags ---------------------------------------------------------------

    fun loadTags() {
        viewModelScope.launch {
            runCatching { api.forumTags(channelId) }
                .onSuccess { tags -> _ui.update { it.copy(tags = tags) } }

        }
    }

    fun clearTagError() = _ui.update { it.copy(tagError = null) }

    fun createTag(name: String, markLabel: String?, onDone: () -> Unit) {
        val clean = name.trim().take(40)
        val cleanLabel = markLabel?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        when {
            _ui.value.creatingTag -> return
            clean.isEmpty() -> { _ui.update { it.copy(tagError = "give the tag a name") }; return }
        }
        _ui.update { it.copy(creatingTag = true, tagError = null) }
        viewModelScope.launch {
            runCatching { api.createForumTag(channelId, clean, cleanLabel) }
                .onSuccess { tag ->
                    _ui.update {
                        it.copy(
                            creatingTag = false,
                            tags = if (it.tags.any { t -> t.id == tag.id }) it.tags else it.tags + tag,
                        )
                    }
                    onDone()
                }
                .onFailure { error ->
                    _ui.update { it.copy(creatingTag = false, tagError = userMessage(error, "couldn't create the tag")) }
                }
        }
    }

    fun setTagFilter(tagId: String?) {
        if (tagId == _ui.value.tagFilter) return
        _ui.update { it.copy(tagFilter = tagId) }
        refresh()
    }

    // --- like / mark --------------------------------------------------------

    fun toggleLike(id: String) {
        val post = _ui.value.posts.firstOrNull { it.id == id } ?: return
        val nowLiked = !post.liked
        val optimisticCount = (post.like_count + if (nowLiked) 1 else -1).coerceAtLeast(0)
        _ui.update { s ->
            s.copy(posts = s.posts.map { if (it.id == id) it.copy(liked = nowLiked, like_count = optimisticCount) else it })
        }
        viewModelScope.launch {
            runCatching { if (nowLiked) api.likeMessage(id) else api.unlikeMessage(id) }
                .onSuccess { res ->
                    _ui.update { s ->
                        s.copy(posts = s.posts.map {
                            if (it.id == id) it.copy(liked = res.liked, like_count = res.like_count) else it
                        })
                    }
                }
                .onFailure { error ->

                    _ui.update { s ->
                        s.copy(
                            posts = s.posts.map {
                                if (it.id == id) it.copy(liked = post.liked, like_count = post.like_count) else it
                            },
                            error = userMessage(error, if (nowLiked) "couldn't like" else "couldn't unlike"),
                        )
                    }
                }
        }
    }

    fun toggleMark(id: String) {
        val marked = _ui.value.openPostMarked
        viewModelScope.launch {
            runCatching { if (marked) api.unmarkMessage(id) else api.markMessage(id) }
                .onSuccess { res ->
                    _ui.update { s ->
                        s.copy(
                            openPostMarked = if (s.openPostId == id) res.marked else s.openPostMarked,
                            posts = s.posts.map { if (it.id == id) it.copy(marked = res.marked) else it },
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(error = userMessage(error, if (marked) "couldn't unmark" else "couldn't mark")) }
                }
        }
    }

    // --- @mentions ----------------------------------------------------------

    fun loadMentionCandidates() {
        if (mentionsLoaded || mentionJob?.isActive == true) return
        mentionJob = viewModelScope.launch {
            runCatching {
                val space = social.spaces().firstOrNull { s -> s.channels.any { it.id == channelId } }
                    ?: return@runCatching
                val members = social.spaceMembers(space.id)
                _ui.update { state ->
                    state.copy(mentionCandidates = members.map { m ->
                        ForumMentionCandidate(m.id, m.username, m.display_name, m.avatar_key)
                    })
                }
            }.onSuccess { mentionsLoaded = true }
        }
    }

    fun mediaUrl(key: String?): String? = key?.let { api.mediaUrl(it) }

    private fun userMessage(error: Throwable, fallback: String): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
