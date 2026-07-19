package app.pigeonsms.ui.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.network.ForumPostDto
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

fun forumPostTitle(metadata: JsonElement?): String = runCatching {
    metadata?.jsonObject?.get("title")?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "untitled post"

class ForumViewModel(
    private val api: PigeonApi,
    gateway: Gateway,
    private val channelId: String,
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
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _ui = MutableStateFlow(ForumUiState())
    val ui: StateFlow<ForumUiState> = _ui
    private var listJob: Job? = null

    init {
        refresh()
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
            runCatching { api.forumPosts(channelId, _ui.value.sort) }
                .onSuccess { posts ->
                    _ui.update { it.copy(posts = posts, loading = false, error = null) }
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

    fun createPost(title: String, content: String, onDone: () -> Unit) {
        val cleanTitle = title.trim().take(160)
        val cleanContent = content.trim().take(4000)
        when {
            _ui.value.creating -> return
            cleanTitle.isEmpty() -> { _ui.update { it.copy(createError = "give your post a title") }; return }
            cleanContent.isEmpty() -> { _ui.update { it.copy(createError = "write something first") }; return }
        }
        _ui.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            runCatching { api.createForumPost(channelId, cleanTitle, cleanContent, UUID.randomUUID().toString()) }
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
        _ui.update { it.copy(openPostId = postId, thread = ThreadState(loading = true)) }
        viewModelScope.launch {
            runCatching { api.forumThread(channelId, postId) }
                .onSuccess { thread ->
                    _ui.update { state ->
                        if (state.openPostId != postId) state
                        else state.copy(thread = ThreadState(post = thread.post, replies = thread.replies))
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

    fun closePost() = _ui.update { it.copy(openPostId = null, thread = ThreadState()) }

    fun sendReply(content: String, onSent: () -> Unit) {
        val postId = _ui.value.openPostId ?: return
        val clean = content.trim().take(4000)
        if (clean.isEmpty() || _ui.value.thread.sending) return
        _ui.update { it.copy(thread = it.thread.copy(sending = true, sendError = null)) }
        viewModelScope.launch {
            runCatching { api.createForumReply(channelId, postId, clean, UUID.randomUUID().toString()) }
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

    fun mediaUrl(key: String?): String? = key?.let { api.mediaUrl(it) }

    private fun userMessage(error: Throwable, fallback: String): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
