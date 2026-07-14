package app.pigeonsms.data

import androidx.room.withTransaction
import app.pigeonsms.db.ChannelCursorEntity
import app.pigeonsms.db.MessageEntity
import app.pigeonsms.db.OutboxEntity
import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.network.AttachmentDto
import app.pigeonsms.network.MessageDto
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.PollDto
import app.pigeonsms.network.PollOptionCountDto
import app.pigeonsms.network.ReactionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface PinEvent {
    val channelId: String
    data class Pinned(override val channelId: String, val messageId: String) : PinEvent
    data class Unpinned(override val channelId: String, val messageId: String) : PinEvent
    data class SuperPinSet(override val channelId: String, val message: MessageDto) : PinEvent
    data class SuperPinRemoved(override val channelId: String) : PinEvent
}

class ChatRepository(private val api: PigeonApi, private val db: PigeonDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val reactionMutex = Mutex()
    private val pollMutex = Mutex()

    private val _reads = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val reads: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, Long>>> = _reads

    fun clearReads() { _reads.value = emptyMap() }

    private val _pinEvents = kotlinx.coroutines.flow.MutableSharedFlow<PinEvent>(extraBufferCapacity = 16)
    val pinEvents: kotlinx.coroutines.flow.SharedFlow<PinEvent> = _pinEvents

    fun applyPinEvent(event: PinEvent) { _pinEvents.tryEmit(event) }

    fun applyReadEvent(channelId: String, userId: String, seq: Long) {
        _reads.value = _reads.value.toMutableMap().apply {
            val channel = (this[channelId] ?: emptyMap()).toMutableMap()
            channel[userId] = maxOf(channel[userId] ?: 0L, seq)
            this[channelId] = channel
        }
    }

    fun mediaUrl(key: String) = api.mediaUrl(key)

    fun stream(channelId: String): Flow<List<MessageEntity>> =
        db.messages().stream(channelId).map { list -> list.distinctBy { it.id } }

    private fun MessageDto.toEntity(state: String = "SENT") = MessageEntity(
        id = id, channelId = channel_id, seq = seq, authorId = author.id,
        authorName = author.display_name ?: author.username, authorAvatar = author.avatar_key,
        authorAccent = author.accent, content = content, replyTo = reply_to, nonce = nonce,
        attachmentKey = attachment?.key, attachmentName = attachment?.name,
        attachmentType = attachment?.type, attachmentSize = attachment?.size,
        createdAt = created_at, editedAt = edited_at, deleted = deleted,
        reactionsJson = json.encodeToString(reactions), revisionsJson = revisions?.let { json.encodeToString(it) },
        kind = kind, metadataJson = metadata?.toString(), pollJson = poll?.let { json.encodeToString(it) },
        state = state,
    )

    suspend fun sync(channelId: String) {
        val lastKnown = db.cursors().get(channelId)
        val page = api.messagesPage(channelId)
        val remote = page.messages
        db.withTransaction { remote.forEach { mergeEvent(it) } }
        if (lastKnown != null) {
            var oldest = remote.minOfOrNull { it.seq }
            var guard = 0
            while (oldest != null && oldest > lastKnown + 1 && guard++ < 20) {
                val older = api.messages(channelId, before = oldest)
                if (older.isEmpty()) break
                db.withTransaction { older.forEach { mergeEvent(it) } }
                oldest = older.minOfOrNull { it.seq }
            }
            if (oldest != null && oldest > lastKnown + 1) {
                db.messages().deleteBelow(channelId, oldest)
            }
        }
        remote.maxOfOrNull { it.seq }?.let { db.cursors().set(ChannelCursorEntity(channelId, it)) }
        page.read?.forEach { (userId, seq) -> applyReadEvent(channelId, userId, seq) }
    }

    suspend fun loadOlder(channelId: String): Boolean {
        val oldest = db.messages().oldestSeq(channelId) ?: return false
        val older = api.messages(channelId, before = oldest)
        db.withTransaction { older.forEach { mergeEvent(it) } }
        return older.isNotEmpty()
    }

    suspend fun send(channelId: String, content: String, replyTo: String?, attachment: AttachmentDto?, selfName: String) {
        val nonce = "${System.currentTimeMillis()}-${(0..9999).random()}"
        val now = System.currentTimeMillis()
        db.messages().upsertOne(
            MessageEntity(
                id = "pending-$nonce", channelId = channelId, seq = Long.MAX_VALUE / 2 + now % 1000,
                authorId = "me", authorName = selfName, authorAvatar = null, authorAccent = null,
                content = content, replyTo = replyTo, nonce = nonce, attachmentKey = attachment?.key,
                attachmentName = attachment?.name, attachmentType = attachment?.type, attachmentSize = attachment?.size,
                createdAt = now, editedAt = null, deleted = false, reactionsJson = "[]", revisionsJson = null,
                state = "SENDING",
            )
        )
        db.outbox().add(OutboxEntity(nonce, channelId, content, replyTo, attachment?.key, attachment?.name, attachment?.type, attachment?.size, now))
    }

    suspend fun flushOutbox(channelId: String? = null) {
        val items = if (channelId != null) db.outbox().forChannel(channelId) else db.outbox().all()
        for (item in items) {
            try {
                val att = item.attachmentKey?.let { AttachmentDto(it, item.attachmentName, item.attachmentType, item.attachmentSize) }
                val sent = api.sendMessage(item.channelId, item.content, item.nonce, item.replyTo, att)
                db.withTransaction {
                    db.messages().delete("pending-${item.nonce}")
                    db.messages().upsertOne(sent.toEntity())
                    db.outbox().remove(item.nonce)
                }
            } catch (_: Exception) {
                db.outbox().bumpAttempt(item.nonce)
                db.messages().setState(item.nonce, "FAILED")
            }
        }
    }

    suspend fun applyEvent(dto: MessageDto) = mergeEvent(dto)
    suspend fun applyDelete(id: String) = db.messages().markDeleted(id)

    suspend fun sendPoll(channelId: String, question: String, options: List<String>, anonymous: Boolean) {
        val nonce = "${System.currentTimeMillis()}-${(0..9999).random()}"
        mergeEvent(api.sendPoll(channelId, question, options, anonymous, nonce))
    }

    suspend fun sendEvent(
        channelId: String,
        title: String,
        startsAt: Long,
        endsAt: Long? = null,
        location: String? = null,
        description: String? = null,
    ) {
        val nonce = "${System.currentTimeMillis()}-${(0..9999).random()}"
        mergeEvent(api.sendEvent(channelId, title, startsAt, endsAt, location, description, nonce))
    }

    suspend fun votePoll(messageId: String, optionId: String?) = pollMutex.withLock {
        val before = db.messages().byId(messageId) ?: return@withLock
        val beforeJson = before.pollJson ?: return@withLock
        val poll = decodePoll(beforeJson) ?: return@withLock
        val optimistic = movePollVote(poll, optionId)
        val optimisticJson = json.encodeToString(optimistic)
        if (optimisticJson != beforeJson) db.messages().upsertOne(before.copy(pollJson = optimisticJson))

        try {
            val response = if (optionId != null) api.votePoll(messageId, optionId) else api.retractPollVote(messageId)
            val authoritative = response.poll ?: return@withLock
            db.messages().byId(messageId)?.let { latest ->
                db.messages().upsertOne(latest.copy(pollJson = json.encodeToString(authoritative)))
            }
        } catch (error: Exception) {
            val latest = db.messages().byId(messageId)
            if (latest != null && latest.pollJson == optimisticJson) {
                db.messages().upsertOne(latest.copy(pollJson = beforeJson))
            }
            throw error
        }
    }

    suspend fun applyPollUpdate(messageId: String, counts: List<PollOptionCountDto>) = pollMutex.withLock {
        val message = db.messages().byId(messageId) ?: return@withLock
        val poll = message.pollJson?.let(::decodePoll) ?: return@withLock
        val byId = counts.associate { it.id to it.votes.coerceAtLeast(0) }
        val options = poll.options.map { option -> byId[option.id]?.let { option.copy(votes = it) } ?: option }
        val updated = poll.copy(options = options, total_votes = options.sumOf { it.votes })
        db.messages().upsertOne(message.copy(pollJson = json.encodeToString(updated)))
    }

    suspend fun edit(id: String, content: String) = mergeEvent(api.editMessage(id, content))
    suspend fun delete(id: String) {
        api.deleteMessage(id)
        db.messages().markDeleted(id)
    }

    suspend fun react(id: String, emoji: String, on: Boolean) = reactionMutex.withLock {
        val before = db.messages().byId(id)
        val beforeJson = before?.reactionsJson
        val current = beforeJson?.let(::decodeReactions).orEmpty()
        val optimistic = updateReaction(current, emoji, on)
        val optimisticJson = json.encodeToString(optimistic)

        if (before != null && optimisticJson != beforeJson) {
            db.messages().upsertOne(before.copy(reactionsJson = optimisticJson))
        }

        try {
            val response = if (on) api.addReaction(id, emoji) else api.removeReaction(id, emoji)
            val latest = db.messages().byId(id)
            if (latest != null) {
                val reconciled = setReaction(decodeReactions(latest.reactionsJson), response.reaction)
                db.messages().upsertOne(latest.copy(reactionsJson = json.encodeToString(reconciled)))
            }
        } catch (error: Exception) {
            val latest = db.messages().byId(id)
            if (before != null && latest?.reactionsJson == optimisticJson) {
                db.messages().upsertOne(before)
            }
            throw error
        }
    }

    suspend fun applyReactionEvent(id: String, emoji: String, count: Int, me: Boolean?) =
        reactionMutex.withLock {
            val message = db.messages().byId(id) ?: return@withLock
            if (message.deleted) return@withLock
            val current = decodeReactions(message.reactionsJson)
            val existing = current.firstOrNull { it.emoji == emoji }
            val authoritative = ReactionDto(emoji, count.coerceAtLeast(0), me ?: existing?.me ?: false)
            val updated = setReaction(current, authoritative)
            db.messages().upsertOne(message.copy(reactionsJson = json.encodeToString(updated)))
        }

    suspend fun retry(nonce: String, channelId: String) {
        db.messages().setState(nonce, "SENDING")
        flushOutbox(channelId)
    }

    suspend fun pin(id: String, on: Boolean) = if (on) api.pin(id) else api.unpin(id)
    suspend fun pins(channelId: String) = api.pins(channelId)
    suspend fun superPin(channelId: String) = api.superPin(channelId)
    suspend fun setSuperPin(messageId: String) = api.setSuperPin(messageId)
    suspend fun removeSuperPin(channelId: String) = api.removeSuperPin(channelId)
    suspend fun dismissSuperPin(channelId: String) = api.dismissSuperPin(channelId)
    suspend fun search(channelId: String, q: String) = api.search(channelId, q)
    suspend fun typing(channelId: String) = api.typing(channelId)
    suspend fun markRead(channelId: String, seq: Long) = api.markRead(channelId, seq)
    suspend fun uploadFile(bytes: ByteArray, filename: String, type: String) = api.uploadFile(bytes, filename, type)

    private fun decodeReactions(value: String): List<ReactionDto> =
        runCatching { json.decodeFromString<List<ReactionDto>>(value) }.getOrDefault(emptyList())

    private fun decodePoll(value: String): PollDto? =
        runCatching { json.decodeFromString<PollDto>(value) }.getOrNull()

    private fun movePollVote(poll: PollDto, optionId: String?): PollDto {
        val current = poll.options.firstOrNull { it.me }
        if (current?.id == optionId || (current == null && optionId == null)) return poll
        val options = poll.options.map { option ->
            when {
                option.me && option.id != optionId ->
                    option.copy(me = false, votes = (option.votes - 1).coerceAtLeast(0))
                !option.me && option.id == optionId -> option.copy(me = true, votes = option.votes + 1)
                else -> option
            }
        }
        return poll.copy(options = options, total_votes = options.sumOf { it.votes })
    }

    private suspend fun mergeEvent(dto: MessageDto) {
        val incoming = dto.toEntity()
        val current = db.messages().byId(dto.id)
        val currentVersion = current?.let { it.editedAt ?: it.createdAt } ?: Long.MIN_VALUE
        val incomingVersion = incoming.editedAt ?: incoming.createdAt
        val stale = current != null && (
            (current.deleted && !incoming.deleted) ||
                (!incoming.deleted && incomingVersion < currentVersion)
            )
        if (!stale) db.messages().upsertOne(incoming)
    }

    private fun setReaction(reactions: List<ReactionDto>, value: ReactionDto): List<ReactionDto> {
        if (value.count <= 0) return reactions.filterNot { it.emoji == value.emoji }
        return if (reactions.any { it.emoji == value.emoji }) {
            reactions.map { if (it.emoji == value.emoji) value else it }
        } else {
            reactions + value
        }
    }

    private fun updateReaction(
        reactions: List<ReactionDto>,
        emoji: String,
        on: Boolean,
    ): List<ReactionDto> {
        val existing = reactions.firstOrNull { it.emoji == emoji }
        if (on && existing?.me == true) return reactions
        if (!on && existing?.me != true) return reactions

        return when {
            on && existing == null -> reactions + ReactionDto(emoji = emoji, count = 1, me = true)
            on -> reactions.map { reaction ->
                if (reaction.emoji == emoji) reaction.copy(count = reaction.count + 1, me = true) else reaction
            }
            existing != null && existing.count <= 1 -> reactions.filterNot { it.emoji == emoji }
            else -> reactions.map { reaction ->
                if (reaction.emoji == emoji) reaction.copy(count = (reaction.count - 1).coerceAtLeast(0), me = false) else reaction
            }
        }
    }
}
