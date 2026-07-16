package app.pigeonsms.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.ChatRepository
import app.pigeonsms.data.PinEvent
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.db.MessageDao
import app.pigeonsms.db.MessageEntity
import app.pigeonsms.network.MessageDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MentionCandidate(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val avatarKey: String? = null,
)

data class ChatUiState(
    val initialLoading: Boolean = true,
    val loadingOlder: Boolean = false,
    val canLoadOlder: Boolean = true,
    val sending: Boolean = false,
    val composerClearToken: Int = 0,
    val typingUser: String? = null,
    val replyTo: MessageEntity? = null,
    val editing: MessageEntity? = null,
    val searchOpen: Boolean = false,
    val searchResults: List<MessageDto> = emptyList(),
    val searching: Boolean = false,
    val localSearchResults: List<MessageEntity> = emptyList(),
    val localSearching: Boolean = false,
    val pinsOpen: Boolean = false,
    val pins: List<MessageDto> = emptyList(),
    val pinnedMessageIds: Set<String> = emptySet(),
    val superPin: MessageDto? = null,
    val loadingPins: Boolean = false,
    val busyMessageIds: Set<String> = emptySet(),
    val error: String? = null,
    val isAdmin: Boolean = false,
    val peerReadSeq: Long = 0,
    val mentionCandidates: List<MentionCandidate> = emptyList(),
    val canMentionEveryone: Boolean = false,
)

class ChatViewModel(
    private val repo: ChatRepository,
    private val channelId: String,
    private val selfId: String,
    private val selfName: String,
    isAdmin: Boolean,
    private val messageDao: MessageDao,
    private val social: SocialRepository? = null,
    private val isSpace: Boolean = false,
) : ViewModel() {
    val messages: StateFlow<List<MessageEntity>> =
        repo.stream(channelId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val media: StateFlow<List<MessageEntity>> =
        messageDao.mediaStream(channelId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ui = MutableStateFlow(ChatUiState(isAdmin = isAdmin))
    val ui: StateFlow<ChatUiState> = _ui

    private var searchJob: Job? = null
    private var localSearchJob: Job? = null
    private var mentionJob: Job? = null
    private var mentionsLoaded = false
    private var typingClearJob: Job? = null
    private var lastTypingSentAt = 0L

    init {
        refresh()
        viewModelScope.launch { runCatching { repo.flushOutbox(channelId) } }
        refreshPins(showLoading = false)
        refreshSuperPin()
        viewModelScope.launch {
            repo.reads.collect { all ->
                val peerSeq = all[channelId]?.filterKeys { it != selfId }?.values?.maxOrNull() ?: 0L
                if (peerSeq != _ui.value.peerReadSeq) _ui.update { it.copy(peerReadSeq = peerSeq) }
            }
        }
        viewModelScope.launch {
            repo.pinEvents.collect { event ->
                if (event.channelId != channelId) return@collect
                when (event) {
                    is PinEvent.Pinned -> {
                        _ui.update { it.copy(pinnedMessageIds = it.pinnedMessageIds + event.messageId) }
                        if (_ui.value.pinsOpen) refreshPins(showLoading = false)
                    }
                    is PinEvent.Unpinned -> _ui.update { state ->
                        state.copy(
                            pinnedMessageIds = state.pinnedMessageIds - event.messageId,
                            pins = state.pins.filterNot { it.id == event.messageId },
                        )
                    }
                    is PinEvent.SuperPinSet -> _ui.update { it.copy(superPin = event.message) }
                    is PinEvent.SuperPinRemoved -> _ui.update { it.copy(superPin = null) }
                }
            }
        }
    }

    fun mediaUrl(key: String) = repo.mediaUrl(key)

    fun isOwn(message: MessageEntity): Boolean =
        message.authorId == selfId || message.authorId == "me"

    fun refresh() {
        _ui.update { it.copy(initialLoading = messages.value.isEmpty(), error = null) }
        viewModelScope.launch {
            runCatching { repo.sync(channelId) }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't refresh messages") } }
            _ui.update { it.copy(initialLoading = false) }
        }
    }

    fun send(text: String) {
        val content = text.trim()
        val editing = _ui.value.editing
        if (editing != null) {
            if (content.isBlank()) {
                _ui.update { it.copy(error = "a message can't be empty") }
                return
            }
            if (content == editing.content.trim()) {
                _ui.update { it.copy(editing = null) }
                return
            }
            if (!beginMessageAction(editing.id)) return
            _ui.update { it.copy(sending = true, error = null) }
            viewModelScope.launch {
                runCatching { repo.edit(editing.id, content) }
                    .onSuccess {
                        _ui.update { state ->
                            state.copy(editing = null, composerClearToken = state.composerClearToken + 1)
                        }
                    }
                    .onFailure { _ui.update { state -> state.copy(error = "couldn't edit message") } }
                finishMessageAction(editing.id)
                _ui.update { it.copy(sending = false) }
            }
            return
        }

        if (content.isBlank() || _ui.value.sending) return
        val reply = _ui.value.replyTo?.id
        _ui.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.send(channelId, content, reply, null, selfName) }
                .onSuccess {
                    _ui.update { state ->
                        state.copy(replyTo = null, composerClearToken = state.composerClearToken + 1)
                    }
                    runCatching { repo.flushOutbox(channelId) }
                        .onFailure { _ui.update { state -> state.copy(error = "message queued for retry") } }
                }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't queue message") } }
            _ui.update { it.copy(sending = false) }
        }
    }

    fun sendAttachment(bytes: ByteArray, filename: String, type: String, caption: String) {
        if (_ui.value.sending) return
        val reply = _ui.value.replyTo?.id
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            runCatching {
                val attachment = repo.uploadFile(bytes, filename, type)
                repo.send(channelId, caption.trim(), reply, attachment, selfName)
            }.onSuccess {
                _ui.update {
                    it.copy(replyTo = null, composerClearToken = it.composerClearToken + 1)
                }
                runCatching { repo.flushOutbox(channelId) }
                    .onFailure { _ui.update { state -> state.copy(error = "message queued for retry") } }
            }.onFailure {
                _ui.update { it.copy(error = "couldn't upload attachment") }
            }
            _ui.update { it.copy(sending = false) }
        }
    }

    fun setReply(message: MessageEntity?) {
        if (message != null && (message.deleted || message.state != "SENT")) return
        _ui.update { it.copy(replyTo = message, editing = null) }
    }

    fun setEditing(message: MessageEntity?) {
        if (message != null && (!isOwn(message) || message.deleted || message.state != "SENT")) return
        _ui.update { it.copy(editing = message, replyTo = null, error = null) }
    }

    fun delete(message: MessageEntity) {
        if ((!isOwn(message) && !_ui.value.isAdmin) || message.deleted || message.state != "SENT") return
        if (!beginMessageAction(message.id)) return
        viewModelScope.launch {
            runCatching { repo.delete(message.id) }
                .onSuccess {
                    _ui.update { state ->
                        state.copy(
                            editing = state.editing?.takeUnless { it.id == message.id },
                            replyTo = state.replyTo?.takeUnless { it.id == message.id },
                        )
                    }
                }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't delete message") } }
            finishMessageAction(message.id)
        }
    }

    fun toggleReaction(message: MessageEntity, emoji: String, on: Boolean) {
        if (message.deleted || message.state != "SENT" || emoji.isBlank()) return
        if (!beginMessageAction(message.id)) return
        viewModelScope.launch {
            runCatching { repo.react(message.id, emoji, on) }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't update reaction") } }
            finishMessageAction(message.id)
        }
    }

    fun sendPoll(question: String, options: List<String>, anonymous: Boolean) {
        viewModelScope.launch {
            runCatching { repo.sendPoll(channelId, question, options, anonymous) }
                .onFailure { e -> _ui.update { state -> state.copy(error = e.message ?: "couldn't create poll") } }
        }
    }

    fun sendEvent(title: String, startsAt: Long, endsAt: Long?, location: String?, description: String?) {
        viewModelScope.launch {
            runCatching { repo.sendEvent(channelId, title, startsAt, endsAt, location, description) }
                .onFailure { e -> _ui.update { state -> state.copy(error = e.message ?: "couldn't create event") } }
        }
    }

    fun votePoll(message: MessageEntity, optionId: String?) {
        if (message.deleted || message.state != "SENT") return
        if (!beginMessageAction(message.id)) return
        viewModelScope.launch {
            runCatching { repo.votePoll(message.id, optionId) }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't update vote") } }
            finishMessageAction(message.id)
        }
    }

    fun retry(message: MessageEntity) {
        val nonce = message.nonce ?: return
        if (message.state != "FAILED" || !beginMessageAction(message.id)) return
        viewModelScope.launch {
            runCatching { repo.retry(nonce, channelId) }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't retry message") } }
            finishMessageAction(message.id)
        }
    }

    fun typing() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < 3_000) return
        lastTypingSentAt = now
        viewModelScope.launch { runCatching { repo.typing(channelId) } }
    }

    fun loadOlder() {
        val state = _ui.value
        if (state.loadingOlder || !state.canLoadOlder || state.initialLoading || messages.value.isEmpty()) return
        _ui.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            runCatching { repo.loadOlder(channelId) }
                .onSuccess { foundMore -> _ui.update { state -> state.copy(canLoadOlder = foundMore) } }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't load older messages") } }
            _ui.update { it.copy(loadingOlder = false) }
        }
    }

    fun openSearch() = _ui.update { it.copy(searchOpen = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchOpen = false, searchResults = emptyList(), searching = false) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _ui.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _ui.update { it.copy(searching = true) }
            runCatching { repo.search(channelId, q) }
                .onSuccess { results -> _ui.update { state -> state.copy(searchResults = results) } }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't search messages") } }
            _ui.update { it.copy(searching = false) }
        }
    }

    fun localSearch(query: String) {
        localSearchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _ui.update { it.copy(localSearchResults = emptyList(), localSearching = false) }
            return
        }
        localSearchJob = viewModelScope.launch {
            delay(250)
            _ui.update { it.copy(localSearching = true) }
            val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            runCatching { messageDao.searchLocal(channelId, escaped) }
                .onSuccess { results -> _ui.update { state -> state.copy(localSearchResults = results) } }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't search messages") } }
            _ui.update { it.copy(localSearching = false) }
        }
    }

    fun clearLocalSearch() {
        localSearchJob?.cancel()
        _ui.update { it.copy(localSearchResults = emptyList(), localSearching = false) }
    }

    fun loadPins() {
        _ui.update { it.copy(pinsOpen = true) }
        refreshPins(showLoading = true)
    }

    private fun refreshPins(showLoading: Boolean) {
        if (showLoading) _ui.update { it.copy(loadingPins = true) }
        viewModelScope.launch {
            runCatching { repo.pins(channelId) }
                .onSuccess { pins ->
                    _ui.update { state ->
                        state.copy(
                            pins = pins,
                            pinnedMessageIds = pins.map { it.id }.toSet(),
                        )
                    }
                }
                .onFailure {
                    if (showLoading) {
                        _ui.update { state -> state.copy(error = "couldn't load pinned messages") }
                    }
                }
            if (showLoading) _ui.update { it.copy(loadingPins = false) }
        }
    }

    fun closePins() = _ui.update { it.copy(pinsOpen = false) }

    fun refreshSuperPin() = viewModelScope.launch {
        runCatching { repo.superPin(channelId) }.onSuccess { pin ->
            _ui.update { it.copy(superPin = pin?.let { value -> value.message.takeUnless { value.dismissed } }) }
        }
    }

    fun setSuperPin(message: MessageEntity, on: Boolean) = viewModelScope.launch {
        runCatching {
            if (on) repo.setSuperPin(message.id) else repo.removeSuperPin(channelId)
        }.onSuccess { refreshSuperPin() }
            .onFailure { _ui.update { it.copy(error = "couldn't update Super Pin") } }
    }

    fun dismissSuperPin() = viewModelScope.launch {
        runCatching { repo.dismissSuperPin(channelId) }
            .onSuccess { _ui.update { it.copy(superPin = null) } }
    }

    fun pin(message: MessageEntity, on: Boolean) {
        if (message.deleted || message.state != "SENT") return
        setPin(message.id, on)
    }

    fun unpin(messageId: String) = setPin(messageId, false)

    private fun setPin(messageId: String, on: Boolean) {
        if (messageId.isBlank() || !beginMessageAction(messageId)) return
        viewModelScope.launch {
            runCatching { repo.pin(messageId, on) }
                .onSuccess {
                    _ui.update { state ->
                        state.copy(
                            pinnedMessageIds = if (on) {
                                state.pinnedMessageIds + messageId
                            } else {
                                state.pinnedMessageIds - messageId
                            },
                            pins = if (on) state.pins else state.pins.filterNot { it.id == messageId },
                        )
                    }
                    if (_ui.value.pinsOpen) refreshPins(showLoading = false)
                }
                .onFailure { _ui.update { state -> state.copy(error = "couldn't update pin") } }
            finishMessageAction(messageId)
        }
    }

    fun loadMentionCandidates() {
        val social = social ?: return
        if (mentionsLoaded || mentionJob?.isActive == true) return
        mentionJob = viewModelScope.launch {
            runCatching {
                if (isSpace) {
                    val space = social.spaces().firstOrNull { s -> s.channels.any { it.id == channelId } }
                        ?: return@runCatching
                    val members = social.spaceMembers(space.id)
                    _ui.update { state ->
                        state.copy(
                            mentionCandidates = members.map { m ->
                                MentionCandidate(m.id, m.username, m.display_name, m.avatar_key)
                            },
                            canMentionEveryone = space.role == "owner" || space.role == "admin",
                        )
                    }
                } else {
                    val peer = social.dms().firstOrNull { it.channel_id == channelId }?.peer
                        ?: return@runCatching
                    _ui.update { state ->
                        state.copy(
                            mentionCandidates = listOf(
                                MentionCandidate(peer.id, peer.username, peer.display_name, peer.avatar_key),
                            ),
                        )
                    }
                }
            }.onSuccess { mentionsLoaded = true }
        }
    }

    fun onTypingEvent(username: String) {
        if (username.equals(selfName, ignoreCase = true)) return
        typingClearJob?.cancel()
        _ui.update { it.copy(typingUser = username) }
        typingClearJob = viewModelScope.launch {
            delay(4_000)
            _ui.update { it.copy(typingUser = null) }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun reportError(message: String) = _ui.update { it.copy(error = message) }

    fun markRead(seq: Long) = viewModelScope.launch { runCatching { repo.markRead(channelId, seq) } }

    private fun beginMessageAction(id: String): Boolean {
        if (id in _ui.value.busyMessageIds) return false
        _ui.update { it.copy(busyMessageIds = it.busyMessageIds + id, error = null) }
        return true
    }

    private fun finishMessageAction(id: String) {
        _ui.update { it.copy(busyMessageIds = it.busyMessageIds - id) }
    }
}
