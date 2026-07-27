package app.pigeonsms.ui.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.network.AttachmentDto
import app.pigeonsms.network.ForumLikeEventDto
import app.pigeonsms.network.ForumPostDto
import app.pigeonsms.network.ForumTagDto
import app.pigeonsms.network.Gateway
import app.pigeonsms.network.GatewayStatus
import app.pigeonsms.network.MessageDto
import app.pigeonsms.network.PigeonApi
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A picked-and-read image awaiting upload. Bytes are read in the Composable (which
 *  owns a Context); the VM uploads them to get an AttachmentDto. */
data class PickedImage(val bytes: ByteArray, val name: String, val type: String)

/** One candidate for the forum composers' @mention autocomplete. Self-contained
 *  (the chat module's row type is private to that package). */
data class ForumMentionCandidate(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val avatarKey: String? = null,
)

/** Post title lives in metadata.title on kind == "forum_post" messages. */
fun forumPostTitle(metadata: JsonElement?): String = runCatching {
    metadata?.jsonObject?.get("title")?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "untitled post"

/**
 * One forum channel: the post list plus the currently open thread.
 * Backed by the forum endpoints (regular message POST 400s on forum channels):
 *   GET  /channels/:id/forum/posts?sort=active|recent|oldest
 *   GET  /channels/:id/forum/posts/:postId
 *   POST /channels/:id/forum/posts            { title, content, nonce }
 *   POST /channels/:id/forum/posts/:pid/replies { content, nonce }
 * Live updates ride the gateway "forum.post" / "forum.reply" events.
 */
class ForumViewModel(
    private val api: PigeonApi,
    private val social: app.pigeonsms.data.SocialRepository,
    private val gateway: Gateway,
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
        /** Non-null while a thread is open; drives the list<->detail switch. */
        val openPostId: String? = null,
        val thread: ThreadState = ThreadState(),
        /** Live channel title (updated after an owner rename). */
        val title: String = "",
        /** True when the current user owns the space this channel lives in. */
        val isOwner: Boolean = false,
        /** Current user id — for author-scoped actions (delete own post/reply). */
        val selfId: String? = null,
        /** Owning space id — needed for [renameChannel]. */
        val spaceId: String? = null,
        val renaming: Boolean = false,
        /** Pin state for the open post, sourced from the post's `pinned` DTO field
         *  (list row or pins probe) and kept in sync via the `forum.post.update`
         *  gateway event; owner pin/unpin updates it optimistically. */
        val openPostPinned: Boolean = false,
        /** Marked state for the open post (its tag's `mark_label` resolved). */
        val openPostMarked: Boolean = false,
        /** The open post's tag (drives the "mark as <label>" affordance in the thread). */
        val openPostTag: ForumTagDto? = null,
        /** True when the open post is authored by the current user (OP-scoped mark). */
        val openPostIsOp: Boolean = false,
        /** Channel tags (owner-defined) — power the filter row + new-post tag picker. */
        val tags: List<ForumTagDto> = emptyList(),
        /** Active tag filter (tag id) for the list, or null for "all". */
        val tagFilter: String? = null,
        val creatingTag: Boolean = false,
        val tagError: String? = null,
        /** @mention candidates for the composers (space members), lazily loaded. */
        val mentionCandidates: List<ForumMentionCandidate> = emptyList(),
        /** Baseline seq at first visit: posts with a higher seq are "new/unseen". */
        val newSinceSeq: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _ui = MutableStateFlow(ForumUiState(title = initialTitle))
    val ui: StateFlow<ForumUiState> = _ui
    private var listJob: Job? = null
    /** Captured once, before the first read is marked, so the "new post" highlight
     *  survives across refreshes within a single visit. */
    private var newBaselineCaptured = false
    private var mentionsLoaded = false
    private var mentionJob: Job? = null

    /** Requests for a silent (non-user-initiated) refresh, conflated so a busy
     *  thread's flurry of gateway events collapses into at most one re-pull
     *  per [SILENT_REFRESH_MIN_INTERVAL_MS] instead of one per event. */
    private val silentRefreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastSilentRefreshAt = 0L

    init {
        refresh()
        resolveRole()
        loadTags()
        viewModelScope.launch {
            silentRefreshRequests.collect {
                val now = System.currentTimeMillis()
                if (now - lastSilentRefreshAt < SILENT_REFRESH_MIN_INTERVAL_MS) return@collect
                lastSilentRefreshAt = now
                refresh(silent = true)
            }
        }
        viewModelScope.launch {
            // Backfill after a websocket outage: forum events missed while offline
            // are not replayed by the gateway, so re-pull the list (and the open
            // thread, if any) once the connection comes back.
            var wasDown = false
            var hasConnected = false
            gateway.status.collect { status ->
                when {
                    status != GatewayStatus.Connected -> {
                        if (hasConnected) wasDown = true
                    }
                    wasDown -> {
                        wasDown = false
                        refresh()
                        _ui.value.openPostId?.let { openPost(it) }
                    }
                }
                if (status == GatewayStatus.Connected) hasConnected = true
            }
        }
        viewModelScope.launch {
            gateway.events.collect { ev ->
                if (ev.t == "message.delete") {
                    // Tombstone payload: { id, channel_id, seq, deleted, deleted_at }.
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
                            // Deleting the root post closes the open thread with it.
                            openPostId = s.openPostId?.takeUnless { it == id },
                            thread = if (s.openPostId == id) ThreadState()
                            else s.thread.copy(replies = s.thread.replies.filterNot { it.id == id }),
                        )
                    }
                    silentRefreshRequests.tryEmit(Unit) // reply counts + activity ordering
                    return@collect
                }
                // Authoritative like tally for a post (from any member's like/unlike).
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
                // Full post refresh (pin/mark/tag/like changes) — reconcile the list row
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
                    // New root post (possibly our own from another device): re-pull the list quietly.
                    "forum.post" -> silentRefreshRequests.tryEmit(Unit)
                    "forum.reply" -> {
                        val open = _ui.value.openPostId
                        if (open != null && dto.thread_id == open) {
                            _ui.update { state ->
                                if (state.thread.replies.any { it.id == dto.id }) state
                                else state.copy(thread = state.thread.copy(replies = state.thread.replies + dto))
                            }
                        }
                        silentRefreshRequests.tryEmit(Unit) // reply counts + activity ordering
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
                    // Capture the read watermark ONCE, before the first markRead advances
                    // it, so posts newer than the user's last visit stay highlighted for
                    // the whole session on this channel.
                    if (!newBaselineCaptured) {
                        newBaselineCaptured = true
                        val baseline = runCatching { api.channelLastSeq(channelId) }.getOrDefault(0L)
                        _ui.update { it.copy(newSinceSeq = baseline) }
                    }
                    _ui.update { it.copy(posts = posts, loading = false, error = null) }
                    // Replies bump the channel's last_seq past the newest root post,
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
            // Title alone is a valid post now — content (and image) are optional.
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
                    silentRefreshRequests.tryEmit(Unit)
                    onDone()
                    openPost(message.id)
                }
                .onFailure { error ->
                    _ui.update { it.copy(creating = false, createError = userMessage(error, "couldn't create the post")) }
                }
        }
    }

    fun openPost(postId: String) {
        // Seed pin/mark/tag/OP straight from the list row's DTO when we have it, so the
        // thread's indicators are correct immediately (the thread endpoint returns a
        // plain MessageDto without these fields).
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
        // Fall back to the pins list when we don't have the row (deep link / cold open).
        if (row == null) viewModelScope.launch {
            val pinned = runCatching { api.pins(channelId).any { it.id == postId } }.getOrDefault(false)
            _ui.update { if (it.openPostId == postId) it.copy(openPostPinned = pinned) else it }
        }
        viewModelScope.launch {
            runCatching { api.forumThread(channelId, postId) }
                .onSuccess { thread ->
                    // Ignore stale loads if the user already navigated elsewhere.
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
                                // The gateway echo may have landed first — dedupe by id.
                                replies = if (state.thread.replies.any { it.id == message.id }) {
                                    state.thread.replies
                                } else {
                                    state.thread.replies + message
                                },
                            ),
                        )
                    }
                    onSent()
                    silentRefreshRequests.tryEmit(Unit)
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(thread = it.thread.copy(sending = false, sendError = userMessage(error, "couldn't send the reply")))
                    }
                }
        }
    }

    /** Resolve the current user id + whether they own this channel's space. Drives
     *  owner-only affordances (rename/pin) and author-scoped delete. Best-effort:
     *  failure just leaves everything gated off. */
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

    /** True when the current user may delete [authorId]'s post/reply (author or owner). */
    fun canDelete(authorId: String): Boolean {
        val s = _ui.value
        return s.isOwner || (s.selfId != null && s.selfId == authorId)
    }

    fun renameChannel(name: String, onDone: () -> Unit) {
        val spaceId = _ui.value.spaceId ?: return
        // Match the server's channel-name rules (lowercase, hyphen-separated slug),
        // mirroring SpacesViewModel so the PATCH isn't rejected.
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

    /** Delete a post or reply (author or owner). Removes it locally on success; the
     *  gateway `message.delete` tombstone will also arrive and reconcile. */
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
                    silentRefreshRequests.tryEmit(Unit)
                }
                .onFailure { error ->
                    _ui.update { it.copy(error = userMessage(error, "couldn't delete")) }
                }
        }
    }

    /** Toggle pin on the open post (owner only). The `pinned` DTO field is the source
     *  of truth; we reflect the new value optimistically here and on the list row, and
     *  the `forum.post.update` gateway event reconciles other clients. */
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

    /** Pull the channel's owner-defined tags (filter chips + new-post picker + mark labels). */
    fun loadTags() {
        viewModelScope.launch {
            runCatching { api.forumTags(channelId) }
                .onSuccess { tags -> _ui.update { it.copy(tags = tags) } }
            // Silent: tags are additive chrome; a failure just leaves the row empty.
        }
    }

    fun clearTagError() = _ui.update { it.copy(tagError = null) }

    /** Owner-only: define a tag. [markLabel] (non-null) makes the tag "markable" —
     *  posts carrying it can then be marked as e.g. "completed". */
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

    /** Set (or clear, with null) the active tag filter and reload the list. */
    fun setTagFilter(tagId: String?) {
        if (tagId == _ui.value.tagFilter) return
        _ui.update { it.copy(tagFilter = tagId) }
        refresh()
    }

    // --- like / mark --------------------------------------------------------

    /** Toggle the current user's like on a post. Optimistic; the `forum.like` gateway
     *  event carries the authoritative tally and reconciles the count. */
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
                    // Roll the optimistic change back to the server-known values.
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

    /** Toggle "mark" on the open post (OP or owner; the post's tag must be markable).
     *  Reflects the resolved [marked] into the open-thread state and the list row. */
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

    /** Lazily load @mention candidates (the owning space's members) the first time a
     *  composer sees an "@". Mirrors the chat composer's source; failure stays unloaded
     *  so the next "@" retries. Mentions are resolved server-side from the raw text. */
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

    private companion object {
        /** Minimum spacing between silent (gateway-event-driven) refreshes, so a busy
         *  thread's flurry of events collapses into at most one re-pull per window. */
        const val SILENT_REFRESH_MIN_INTERVAL_MS = 750L
    }
}
