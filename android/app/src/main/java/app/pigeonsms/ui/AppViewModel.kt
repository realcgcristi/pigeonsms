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

/** Owns the gateway connection and the home-level social snapshot. */
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

    /** In-app heads-up for a new message in a channel you're not looking at. */
    data class IncomingPing(val channelId: String, val title: String, val preview: String)
    private val _pings = MutableSharedFlow<IncomingPing>(extraBufferCapacity = 8)
    val pings: SharedFlow<IncomingPing> = _pings

    data class IncomingCall(val channelId: String, val title: String, val mode: String, val isSpace: Boolean)
    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall: StateFlow<IncomingCall?> = _incomingCall

    fun clearIncomingCall(call: IncomingCall? = _incomingCall.value) {
        if (call != null && _incomingCall.value == call) _incomingCall.value = null
    }
    private var dmsJob: Job? = null
    private var friendsJob: Job? = null
    private var spacesJob: Job? = null
    private var spacesRefreshPending = false

    /** Set by whichever ChatScreen is open, so its channel's events refresh Room. */
    var activeChannel: String? = null

    init {
        gateway.start()
        viewModelScope.launch {
            // Backfill after a websocket outage: events missed while offline
            // are not replayed by the gateway, so re-sync the open channel and
            // do a full home refresh (dms + friends + spaces).
            var wasDown = false
            var hasConnected = false
            gateway.status.collect { status ->
                when {
                    status != GatewayStatus.Connected -> {
                        // Only treat this as "was down" once we've actually seen a
                        // Connected before — otherwise the very first Connecting/
                        // Disconnected at startup would trigger a spurious extra
                        // refresh on top of the init refresh() below.
                        if (hasConnected) wasDown = true
                    }
                    wasDown -> {
                        wasDown = false
                        activeChannel?.let { runCatching { chat.sync(it) } }
                        refresh()
                    }
                }
                if (status == GatewayStatus.Connected) hasConnected = true
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
                                    // Gateway payloads from newer servers may
                                    // include display names directly. Older
                                    // payloads can still be resolved from the
                                    // cached Spaces snapshot, then gracefully
                                    // fall back to a DM-style sender title.
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
                    "call.incoming" -> {
                        val d = ev.d.jsonObject
                        val channelId = d["channelId"]?.jsonPrimitive?.content
                        val mode = d["mode"]?.jsonPrimitive?.content ?: "voice"
                        val callerUsername = d["from"]?.jsonObject?.get("username")?.jsonPrimitive?.content ?: "someone"
                        if (channelId != null) {
                            val isSpaceChannel = _home.value.spaces.any { space -> space.channels.any { it.id == channelId } }
                            _incomingCall.value = IncomingCall(channelId, "@$callerUsername", mode, isSpaceChannel)
                        }
                    }
                    "call.missed", "call.cancelled" -> {
                        val channelId = ev.d.jsonObject["channelId"]?.jsonPrimitive?.content
                        if (channelId != null && _incomingCall.value?.channelId == channelId) {
                            _incomingCall.value = null
                        }
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
                            chat.applyEvent(event.message) // keep Room's copy of the banner message current
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

        // A session switch must not retain the previous account's cached
        // read markers, refresh jobs, or websocket identity.
        dmsJob?.cancelAndJoin()
        friendsJob?.cancelAndJoin()
        spacesJob?.cancelAndJoin()
        selfId = userId
        sessionIdentity = identity
        // The shell cache is per-account: without this the incoming session would
        // briefly render the previous user's nests, DMs and friends from Room
        // before the first network refresh lands.
        social.clearCache()
        _home.update { it.copy(dms = emptyList(), spaces = emptyList(), friends = emptyList(), incoming = emptyList(), outgoing = emptyList()) }
        chat.clearReads()
        gateway.stop()
        gateway.start()
        refresh()
    }

    /**
     * The three refreshes below all follow the same offline-first shape (v2.9.0):
     *
     *  1. if we're holding nothing yet, paint the Room cache immediately, so a cold
     *     start shows the user's actual nests/DMs/friends instead of a spinner over
     *     an empty screen;
     *  2. hit the network and replace on success (which also rewrites the cache);
     *  3. on failure, fall back to the cache — and only surface an error if there
     *     is genuinely nothing to show, since an error banner over a perfectly good
     *     cached list is just noise on a flaky connection.
     */
    fun refreshDms() {
        dmsJob?.cancel()
        dmsJob = viewModelScope.launch {
            _home.update { it.copy(dmsLoading = true, dmsError = null) }
            if (_home.value.dms.isEmpty()) {
                val cached = social.cachedDms()
                if (cached.isNotEmpty()) _home.update { it.copy(dms = cached) }
            }
            try {
                val dms = social.dms()
                _home.update { it.copy(dms = dms, dmsLoading = false, dmsError = null) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                val cached = social.cachedDms()
                _home.update {
                    it.copy(
                        dms = it.dms.ifEmpty { cached },
                        dmsLoading = false,
                        dmsError = if (it.dms.isEmpty() && cached.isEmpty()) {
                            userMessage(error, "couldn't load messages")
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    fun refreshFriends() {
        friendsJob?.cancel()
        friendsJob = viewModelScope.launch {
            _home.update { it.copy(friendsLoading = true, friendsError = null) }
            if (_home.value.friends.isEmpty() && _home.value.incoming.isEmpty() && _home.value.outgoing.isEmpty()) {
                val cached = social.cachedFriends()
                if (cached.friends.isNotEmpty() || cached.incoming.isNotEmpty() || cached.outgoing.isNotEmpty()) {
                    _home.update {
                        it.copy(friends = cached.friends, incoming = cached.incoming, outgoing = cached.outgoing)
                    }
                }
            }
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
                val cached = social.cachedFriends()
                _home.update {
                    val holding = it.friends.isNotEmpty() || it.incoming.isNotEmpty() || it.outgoing.isNotEmpty()
                    val cachedEmpty = cached.friends.isEmpty() && cached.incoming.isEmpty() && cached.outgoing.isEmpty()
                    it.copy(
                        friends = if (holding) it.friends else cached.friends,
                        incoming = if (holding) it.incoming else cached.incoming,
                        outgoing = if (holding) it.outgoing else cached.outgoing,
                        friendsLoading = false,
                        friendsError = if (!holding && cachedEmpty) {
                            userMessage(error, "couldn't load friends")
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    fun refreshSpaces() {
        spacesJob?.cancel()
        spacesJob = viewModelScope.launch {
            _home.update { it.copy(spacesLoading = true, spacesError = null) }
            if (_home.value.spaces.isEmpty()) {
                val cached = social.cachedSpaces()
                if (cached.isNotEmpty()) _home.update { it.copy(spaces = cached) }
            }
            try {
                val spaces = social.spaces()
                _home.update { it.copy(spaces = spaces, spacesLoading = false, spacesError = null) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                val cached = social.cachedSpaces()
                _home.update {
                    it.copy(
                        spaces = it.spaces.ifEmpty { cached },
                        spacesLoading = false,
                        spacesError = if (it.spaces.isEmpty() && cached.isEmpty()) {
                            userMessage(error, "couldn't load spaces")
                        } else {
                            null
                        },
                    )
                }
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
            // Signing out must leave nothing of this account on disk for the next
            // person to open the app — the cached nests/DMs/friends included.
            social.clearCache()
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
