package app.pigeonsms.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.formatNotificationTitle
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.data.ChatRepository
import app.pigeonsms.data.PinEvent
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.network.DmDto
import app.pigeonsms.network.FriendDto
import app.pigeonsms.network.Gateway
import app.pigeonsms.network.GatewayStatus
import app.pigeonsms.network.MessageDto
import app.pigeonsms.network.PinEventDto
import app.pigeonsms.network.PollUpdateEventDto
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.network.SuperPinRemoveEventDto
import app.pigeonsms.network.SuperPinSetEventDto
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HomeState(
    val dms: List<DmDto> = emptyList(),
    val friends: List<FriendDto> = emptyList(),
    val incoming: List<FriendDto> = emptyList(),
    val outgoing: List<FriendDto> = emptyList(),
    val spaces: List<SpaceDto> = emptyList(),
    val dmsLoading: Boolean = true,
    val friendsLoading: Boolean = true,
    val spacesLoading: Boolean = true,
    val dmsError: String? = null,
    val friendsError: String? = null,
    val spacesError: String? = null,
)

class AppViewModel(
    private val gateway: Gateway,
    private val social: SocialRepository,
    private val chat: ChatRepository,
    private val auth: AuthRepository,
    private var selfId: String,
    private var sessionIdentity: String,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home
    val gatewayStatus: StateFlow<GatewayStatus> = gateway.status
    private val _typingEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val typingEvents: SharedFlow<Pair<String, String>> = _typingEvents

    data class IncomingPing(val channelId: String, val title: String, val preview: String)
    private val _pings = MutableSharedFlow<IncomingPing>(extraBufferCapacity = 8)
    val pings: SharedFlow<IncomingPing> = _pings
    private var dmsJob: Job? = null
    private var friendsJob: Job? = null
    private var spacesJob: Job? = null
    private var spacesRefreshPending = false

    var activeChannel: String? = null

    init {
        gateway.start()
        viewModelScope.launch {

            // are not replayed by the gateway, so re-sync the open channel.
            var wasDown = false
            gateway.status.collect { status ->
                when {
                    status != GatewayStatus.Connected -> wasDown = true
                    wasDown -> {
                        wasDown = false
                        activeChannel?.let { runCatching { chat.sync(it) } }
                        refreshDms()
                    }
                }
            }
        }
        viewModelScope.launch {
            gateway.events.collect { ev ->
                when (ev.t) {
                    "message.new", "message.edit", "forum.post", "forum.reply" -> {
                        val dto = runCatching { json.decodeFromJsonElement(MessageDto.serializer(), ev.d) }.getOrNull()
                        if (dto != null) {
                            chat.applyEvent(dto)
                            if (dto.channel_id != activeChannel) {
                                refreshDms()
                                if (ev.t != "message.edit" && dto.author.id != selfId) {

                                    // payloads can still be resolved from the

                                    val eventData = ev.d.jsonObject
                                    val location = _home.value.spaces.asSequence()
                                        .mapNotNull { space ->
                                            space.channels.firstOrNull { it.id == dto.channel_id }
                                                ?.let { channel -> space to channel }
                                        }
                                        .firstOrNull()
                                    val spaceName = eventData["space_name"]?.jsonPrimitive?.content
                                        ?: eventData["space"]?.jsonPrimitive?.content
                                        ?: location?.first?.name
                                    val channelName = eventData["channel_name"]?.jsonPrimitive?.content
                                        ?: eventData["channel"]?.jsonPrimitive?.content
                                        ?: location?.second?.name
                                    _pings.tryEmit(
                                        IncomingPing(
                                            channelId = dto.channel_id,
                                            title = formatNotificationTitle(
                                                spaceName = spaceName,
                                                channelName = channelName,
                                                senderUsername = dto.author.username,
                                                fallbackTitle = "@${dto.author.username}",
                                            ),
                                            preview = dto.content.ifBlank { "sent an attachment" },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    "message.delete" -> {
                        val id = ev.d.jsonObject["id"]?.jsonPrimitive?.content
                        if (id != null) chat.applyDelete(id)
                    }
                    "poll.update" -> {
                        val update = runCatching {
                            json.decodeFromJsonElement(PollUpdateEventDto.serializer(), ev.d)
                        }.getOrNull()
                        if (update != null) {
                            viewModelScope.launch {
                                runCatching { chat.applyPollUpdate(update.message_id, update.options) }
                            }
                        }
                    }
                    "pin.add", "pin.remove" -> {
                        val event = runCatching { json.decodeFromJsonElement(PinEventDto.serializer(), ev.d) }.getOrNull()
                        if (event != null) {
                            chat.applyPinEvent(
                                if (ev.t == "pin.add") PinEvent.Pinned(event.channel_id, event.message_id)
                                else PinEvent.Unpinned(event.channel_id, event.message_id),
                            )
                        }
                    }
                    "super_pin.set" -> {
                        val event = runCatching { json.decodeFromJsonElement(SuperPinSetEventDto.serializer(), ev.d) }.getOrNull()
                        if (event != null) {
                            chat.applyEvent(event.message)
                            chat.applyPinEvent(PinEvent.SuperPinSet(event.channel_id, event.message))
                        }
                    }
                    "super_pin.remove" -> {
                        val event = runCatching { json.decodeFromJsonElement(SuperPinRemoveEventDto.serializer(), ev.d) }.getOrNull()
                        if (event != null) chat.applyPinEvent(PinEvent.SuperPinRemoved(event.channel_id))
                    }
                    "space.update", "channel.update", "channel.delete" -> refreshSpaces()
                    "reaction.add", "reaction.remove" -> {
                        val data = ev.d.jsonObject
                        val messageId = data["message_id"]?.jsonPrimitive?.content
                        val emoji = data["emoji"]?.jsonPrimitive?.content
                        val count = data["count"]?.jsonPrimitive?.content?.toIntOrNull()
                        val actorId = data["user_id"]?.jsonPrimitive?.content
                        val active = data["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        if (messageId != null && emoji != null && count != null) {
                            chat.applyReactionEvent(
                                messageId,
                                emoji,
                                count,
                                active.takeIf { actorId == selfId },
                            )
                        }
                    }
                    "typing" -> {
                        val data = ev.d.jsonObject
                        val channelId = data["channel_id"]?.jsonPrimitive?.content
                        val username = data["username"]?.jsonPrimitive?.content
                        if (channelId != null && username != null) {
                            _typingEvents.tryEmit(channelId to username)
                        }
                    }
                    "read" -> {
                        val data = ev.d.jsonObject
                        val channelId = data["channel_id"]?.jsonPrimitive?.content
                        val userId = data["user_id"]?.jsonPrimitive?.content
                        val seq = data["seq"]?.jsonPrimitive?.content?.toLongOrNull()
                        if (channelId != null && userId != null && seq != null) {
                            chat.applyReadEvent(channelId, userId, seq)
                        }
                    }
                    "friend.request", "friend.accept", "channel.new" -> { refreshFriends(); refreshDms() }
                }
            }
        }
        refresh()
    }

    fun refresh() { refreshDms(); refreshFriends(); refreshSpaces() }

    fun registerPushToken(token: String) = viewModelScope.launch { runCatching { auth.registerPush(token) } }

    suspend fun activateSession(userId: String, identity: String) {
        if (selfId == userId && sessionIdentity == identity) return

        // read markers, refresh jobs, or websocket identity.
        dmsJob?.cancelAndJoin()
        friendsJob?.cancelAndJoin()
        spacesJob?.cancelAndJoin()
        selfId = userId
        sessionIdentity = identity
        chat.clearReads()
        gateway.stop()
        gateway.start()
        refresh()
    }

    fun refreshDms() {
        dmsJob?.cancel()
        dmsJob = viewModelScope.launch {
            _home.update { it.copy(dmsLoading = true, dmsError = null) }
            try {
                val dms = social.dms()
                _home.update { it.copy(dms = dms, dmsLoading = false, dmsError = null) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                _home.update { it.copy(dmsLoading = false, dmsError = userMessage(error, "couldn't load messages")) }
            }
        }
    }

    fun refreshFriends() {
        friendsJob?.cancel()
        friendsJob = viewModelScope.launch {
            _home.update { it.copy(friendsLoading = true, friendsError = null) }
            try {
                val friends = social.friends()
                _home.update {
                    it.copy(
                        friends = friends.friends,
                        incoming = friends.incoming,
                        outgoing = friends.outgoing,
                        friendsLoading = false,
                        friendsError = null,
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                _home.update { it.copy(friendsLoading = false, friendsError = userMessage(error, "couldn't load friends")) }
            }
        }
    }

    fun refreshSpaces() {
        spacesJob?.cancel()
        spacesJob = viewModelScope.launch {
            _home.update { it.copy(spacesLoading = true, spacesError = null) }
            try {
                val spaces = social.spaces()
                _home.update { it.copy(spaces = spaces, spacesLoading = false, spacesError = null) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                _home.update { it.copy(spacesLoading = false, spacesError = userMessage(error, "couldn't load spaces")) }
            }
        }
    }

    fun mediaUrl(key: String?): String? = social.mediaUrl(key)

    fun viewModelScopeSignOut() {
        viewModelScope.launch {
            dmsJob?.cancelAndJoin()
            friendsJob?.cancelAndJoin()
            spacesJob?.cancelAndJoin()
            gateway.stop()
            runCatching { auth.logout() }
        }
    }

    override fun onCleared() {
        gateway.stop()
        super.onCleared()
    }

    private fun userMessage(error: Throwable, fallback: String): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
