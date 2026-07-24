package app.pigeonsms.data

import androidx.room.withTransaction
import app.pigeonsms.db.ChannelCursorEntity
import app.pigeonsms.db.MessageEntity
import app.pigeonsms.db.OutboxEntity
import app.pigeonsms.db.PigeonDatabase
import app.pigeonsms.data.e2ee.DevicePub
import app.pigeonsms.data.e2ee.E2eeManager
import app.pigeonsms.network.AttachmentDto
import app.pigeonsms.network.MessageDto
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.PollDto
import app.pigeonsms.network.PollOptionCountDto
import app.pigeonsms.network.ReactionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** Live pin/super-pin changes fanned out by the gateway; chat screens filter by channel. */
sealed interface PinEvent {
    val channelId: String
    data class Pinned(override val channelId: String, val messageId: String) : PinEvent
    data class Unpinned(override val channelId: String, val messageId: String) : PinEvent
    data class SuperPinSet(override val channelId: String, val message: MessageDto) : PinEvent
    data class SuperPinRemoved(override val channelId: String) : PinEvent
}

/**
 * Offline-first messaging. Room is the source of truth for the chat screen;
 * network + gateway feed it. Sends go through the outbox for reliable delivery.
 */
class ChatRepository(
    private val api: PigeonApi,
    private val db: PigeonDatabase,
    /**
     * A9's E2EE façade (EXPERIMENTAL, flag-OFF). Nullable + defaulted so the manual-DI
     * call sites (AppContainer) and CI keep compiling before A9 wires a concrete instance;
     * a null manager means "no encryption available", identical to the flag being off.
     * Referenced by the A9 contract type only — this module never touches A9's internals.
     */
    private val e2ee: E2eeManager? = null,
) {
    private companion object {
        /** Outbox retry budget: past this many failed attempts a message is dead-lettered (FAILED) and no longer retried. */
        const val MAX_OUTBOX_ATTEMPTS = 5
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val reactionMutex = Mutex()
    private val pollMutex = Mutex()
    private val flushMutex = Mutex()

    /**
     * Long-lived scope for fire-and-forget E2EE key-exchange kicked off from [send]. Using
     * a repo-owned SupervisorJob (not the caller's) means the composer coroutine returns
     * immediately and a failing exchange can't cancel or fail the send. Best-effort only.
     */
    private val repoScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * DM channels whose E2EE session (DM key exchanged + ratchet seeded) is established
     * on THIS device, so we only run the exchange once per channel per process. A
     * channelId present here means encrypt/decrypt can be attempted; absence means we'll
     * (re)try [ensureE2eeSession] on the next send/receive. In-memory only — the durable
     * key material lives in the ratchet-state DAO (E2eeManager), so a restart just
     * re-derives "established" from the server envelopes + stored keys idempotently.
     */
    private val establishedSessions = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** Serializes the per-channel key-exchange so two concurrent sends don't both initiate. */
    private val e2eeSessionMutex = Mutex()

    /** Placeholder shown for an encrypted message we can't decrypt (keys missing / not yet established). */
    private val lockedPlaceholder = "🔒 encrypted message"

    /**
     * Per-send delivery metadata keyed by nonce. The outbox table (core/db, owned by
     * A10) has no columns for ttl/send_at/encrypted, so we can't persist them on the
     * OutboxEntity without a schema change outside this module's scope. Instead we hold
     * them in-memory so the immediate flushOutbox() that follows a send picks them up.
     * A restart between enqueue and flush drops the ttl/send_at hints (the message still
     * sends, just without disappearing/scheduling) — noted for the A10 outbox-schema pass.
     */
    private data class SendMeta(val ttl: Long?, val sendAt: Long?, val encrypted: Boolean)
    private val pendingSendMeta = java.util.concurrent.ConcurrentHashMap<String, SendMeta>()

    /**
     * Process-wide monotonic seq for optimistic (pending) rows. Starts above every real
     * server seq so pending messages always sort last, and strictly increases per send so
     * rapid sends never collide — the old `now % 1000` scheme only had 1000 distinct values.
     */
    private val pendingSeq = AtomicLong(Long.MAX_VALUE / 2)

    /** channelId → (userId → last read seq). Seeded on sync, updated by gateway read events. */
    private val _reads = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val reads: kotlinx.coroutines.flow.StateFlow<Map<String, Map<String, Long>>> = _reads

    /** Drop all read markers — session switch must not leak the previous account's "seen". */
    fun clearReads() { _reads.value = emptyMap() }

    /** Pin state isn't cached in Room — gateway pin events fan out to open chat screens here. */
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
        // Gap-fill: when more than one page arrived while offline, the latest
        // page starts above the previously synced seq — walk back until the
        // ranges join, else the cache silently misses the middle.
        var gapClosed = true
        if (lastKnown != null) {
            var oldest = remote.minOfOrNull { it.seq }
            var guard = 0
            while (oldest != null && oldest > lastKnown + 1 && guard++ < 20) {
                val older = api.messages(channelId, before = oldest)
                if (older.isEmpty()) break
                db.withTransaction { older.forEach { mergeEvent(it) } }
                oldest = older.minOfOrNull { it.seq }
            }
            // Gap still open after the page budget (very long offline stretch): do NOT
            // delete the middle history and do NOT advance the cursor past the gap. We
            // keep both cached islands and leave the cursor at lastKnown so the next
            // sync re-detects `oldest > lastKnown + 1` and resumes the walk-back until
            // the ranges finally join. Advancing to the newest seq here would strand the
            // gap forever (the check never re-triggers) and lose the middle range.
            gapClosed = oldest == null || oldest <= lastKnown + 1
        }
        // The cursor is the highest seq below which everything is contiguously cached.
        // Only advance it to the newest seq once the gap (if any) is closed; otherwise
        // leave it where it was so the unfilled range stays re-fetchable.
        if (gapClosed) {
            remote.maxOfOrNull { it.seq }?.let { db.cursors().set(ChannelCursorEntity(channelId, it)) }
        }
        page.read?.forEach { (userId, seq) -> applyReadEvent(channelId, userId, seq) }
    }

    suspend fun loadOlder(channelId: String): Boolean {
        val oldest = db.messages().oldestSeq(channelId) ?: return false
        val older = api.messages(channelId, before = oldest)
        db.withTransaction { older.forEach { mergeEvent(it) } }
        return older.isNotEmpty()
    }

    /**
     * Optimistic send: render immediately, queue for delivery.
     *
     * [ttl] (seconds) marks a disappearing message — the server derives expires_at.
     * [sendAt] (epoch ms in the future) schedules the message server-side instead of
     * sending now. [e2eeEnabled] is the client flag (default OFF): when on AND A9's
     * manager reports keys are established for this DM, the content is encrypted here
     * and flagged encrypted=1 so the server only ever stores ciphertext.
     */
    suspend fun send(
        channelId: String,
        content: String,
        replyTo: String?,
        attachment: AttachmentDto?,
        selfName: String,
        ttl: Long? = null,
        sendAt: Long? = null,
        e2eeEnabled: Boolean = false,
        isDm: Boolean = false,
    ) {
        val nonce = "${System.currentTimeMillis()}-${(0..9999).random()}"
        val now = System.currentTimeMillis()
        // E2EE hook (EXPERIMENTAL): encrypt only for DMs, only when the flag is on and the
        // manager is present. CRITICAL: never run the key exchange (ensureE2eeSession does
        // blocking network round-trips) on the send path — that stalled/failed sends in
        // 2.8.0. Only encrypt when the session is ALREADY established for this channel;
        // otherwise send PLAINTEXT immediately and kick off the exchange in the background
        // (fire-and-forget) so a LATER message in this DM can encrypt. Every E2EE call is
        // wrapped so ANY failure degrades to plaintext — a send NEVER fails because of
        // E2EE. We NEVER encrypt space/forum messages (isDm gates that).
        val mgr = e2ee
        val cipher = if (e2eeEnabled && isDm && mgr != null) {
            if (channelId in establishedSessions) {
                runCatching { mgr.encrypt(channelId, content) }.getOrNull()
            } else {
                // Not established yet: don't block. Establish in the background for next time.
                repoScope.launch { runCatching { ensureE2eeSession(channelId, mgr) } }
                null
            }
        } else {
            null
        }
        val wireContent = cipher ?: content
        val encrypted = cipher != null
        // The local optimistic row shows the plaintext the user just typed; only the
        // outbox/wire copy carries ciphertext.
        db.messages().upsertOne(
            MessageEntity(
                id = "pending-$nonce", channelId = channelId, seq = pendingSeq.incrementAndGet(),
                authorId = "me", authorName = selfName, authorAvatar = null, authorAccent = null,
                content = content, replyTo = replyTo, nonce = nonce, attachmentKey = attachment?.key,
                attachmentName = attachment?.name, attachmentType = attachment?.type, attachmentSize = attachment?.size,
                createdAt = now, editedAt = null, deleted = false, reactionsJson = "[]", revisionsJson = null,
                state = "SENDING",
            )
        )
        pendingSendMeta[nonce] = SendMeta(ttl = ttl, sendAt = sendAt, encrypted = encrypted)
        db.outbox().add(OutboxEntity(nonce, channelId, wireContent, replyTo, attachment?.key, attachment?.name, attachment?.type, attachment?.size, now))
    }

    suspend fun flushOutbox(channelId: String? = null) = flushMutex.withLock {
        // Guarded so init + send-onSuccess can't both drain the same snapshot and double-POST.
        val items = if (channelId != null) db.outbox().forChannel(channelId) else db.outbox().all()
        for (item in items) {
            // Poison message: past the retry budget we stop sending forever and dead-letter it,
            // leaving the outbox row so retry() can still force a resend on user action.
            if (item.attempts >= MAX_OUTBOX_ATTEMPTS) {
                db.messages().setState(item.nonce, "FAILED")
                continue
            }
            try {
                val att = item.attachmentKey?.let { AttachmentDto(it, item.attachmentName, item.attachmentType, item.attachmentSize) }
                // ttl/send_at/encrypted ride the in-memory SendMeta (the outbox row can't
                // carry them until A10 extends OutboxEntity); absent meta = a plain send,
                // which is also the correct post-restart fallback.
                val meta = pendingSendMeta[item.nonce]
                val response = api.sendMessage(
                    item.channelId, item.content, item.nonce, item.replyTo, att,
                    ttl = meta?.ttl, sendAt = meta?.sendAt, encrypted = meta?.encrypted ?: false,
                )
                val sent = response.message
                if (sent == null) {
                    // Scheduled send (server 202 { scheduled }): the message won't arrive
                    // now — it fires later via the cron dispatcher and lands through the
                    // gateway/sync path then. Treat as SUCCESS: drop the optimistic row and
                    // the outbox entry so it isn't marked FAILED or re-POSTed (which would
                    // insert a duplicate scheduled row on every reflush).
                    db.withTransaction {
                        db.messages().delete("pending-${item.nonce}")
                        db.outbox().remove(item.nonce)
                    }
                    pendingSendMeta.remove(item.nonce)
                    continue
                }
                db.withTransaction {
                    db.messages().delete("pending-${item.nonce}")
                    db.messages().upsertOne(sent.toEntity())
                    db.outbox().remove(item.nonce)
                }
                pendingSendMeta.remove(item.nonce)
            } catch (_: Exception) {
                db.outbox().bumpAttempt(item.nonce)
                db.messages().setState(item.nonce, "FAILED")
            }
        }
    }

    suspend fun applyEvent(dto: MessageDto) = mergeEvent(dto)
    suspend fun applyDelete(id: String) = db.messages().markDeleted(id)

    /**
     * Polls and events skip the outbox: rendering needs server-issued option ids
     * and validation (unique options, starts_at) belongs to the server. The nonce
     * still dedupes retries at the API level.
     */
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

    /** Vote for [optionId], or retract with null. Optimistic; reconciled with the server's poll. */
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

    /** Gateway `poll.update`: authoritative counts; local `me` flags are kept. */
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
        db.outbox().resetAttempts(nonce)
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
    /** Space-wide FTS5 search (2.8.0); paginate with [before]. Server skips encrypted messages. */
    suspend fun searchSpace(spaceId: String, q: String, before: Long? = null) = api.searchSpace(spaceId, q, before)
    suspend fun typing(channelId: String) = api.typing(channelId)
    suspend fun markRead(channelId: String, seq: Long) = api.markRead(channelId, seq)
    suspend fun uploadFile(bytes: ByteArray, filename: String, type: String) = api.uploadFile(bytes, filename, type)

    private fun decodeReactions(value: String): List<ReactionDto> =
        runCatching { json.decodeFromString<List<ReactionDto>>(value) }.getOrDefault(emptyList())

    private fun decodePoll(value: String): PollDto? =
        runCatching { json.decodeFromString<PollDto>(value) }.getOrNull()

    /** Single-choice semantics: my vote moves to [optionId] (null clears it). */
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

    // Atomic on EVERY path: the live gateway applyEvent path doesn't wrap this in a
    // transaction of its own, so without withTransaction here `stream` could emit the
    // intermediate state where the pending row is deleted but the authoritative row
    // isn't inserted yet — your own message flickers out then back. Room's
    // withTransaction is re-entrant, so this is a no-op when sync/flushOutbox/loadOlder
    // already opened an outer transaction.
    /**
     * E2EE receive hook (flag-OFF by default). When A9's manager is present and the
     * message is marked encrypted, decrypt for display: on success show plaintext, on
     * missing/unestablished keys (E2eeManager.decrypt throws) show [lockedPlaceholder]
     * instead of raw base64.
     *
     * [isEncrypted] comes from the server's `encrypted=1` flag, now surfaced on
     * [MessageDto.encrypted] and passed through from [mergeEvent]. For plaintext rows
     * (encrypted=false) this is a safe no-op — we never attempt to decrypt (and thus
     * never clobber) plaintext.
     */
    /**
     * Establish (once per channel per process) the E2EE session for a DM so encrypt/
     * decrypt can run. This is the orchestration the manager can't do itself — it needs
     * the API to move device keys + envelopes around. EXPERIMENTAL, best-effort:
     * returns true only if key material is in place, false on ANY failure so callers
     * fall back to plaintext (send) or the locked placeholder (receive). Never throws.
     *
     * Flow (idempotent, both sides converge on the same DM key):
     *  1. RECEIVE first — GET /channels/:id/key-envelopes. If an envelope addressed to
     *     one of our devices exists, unwrapDmKey installs the key + seeds the ratchet as
     *     responder. This wins so a peer who already initiated doesn't get re-initiated.
     *  2. INITIATE — no envelope yet: resolve the DM peer (api.dms()), fetch the peer's
     *     device pub keys (+ our own other devices) and wrapDmKeyFor mints/warps the DM
     *     key to all of them, then POST the envelopes. wrapDmKeyFor also seeds our
     *     ratchet as initiator.
     *
     * [manager] is passed in so the null-check happens once at the call site.
     */
    private suspend fun ensureE2eeSession(channelId: String, manager: E2eeManager): Boolean {
        if (channelId in establishedSessions) return true
        return e2eeSessionMutex.withLock {
            if (channelId in establishedSessions) return@withLock true
            val ok = runCatching {
                // 1. Receive path: is there already a key wrapped to us?
                val envelopes = runCatching { api.getKeyEnvelopes(channelId) }.getOrDefault(emptyList())
                val mine = envelopes.firstOrNull { it.wrapped_key.isNotEmpty() }
                if (mine != null) {
                    manager.unwrapDmKey(channelId, mine.wrapped_key)
                    return@runCatching true
                }
                // 2. Initiate path: find the DM peer and wrap the key to every device.
                val peerId = api.dms().firstOrNull { it.channel_id == channelId }?.peer?.id
                    ?: return@runCatching false // not a resolvable DM (or group/space) — no E2EE
                val peerDevices = api.userDevices(peerId)
                    .filter { it.pub_key.isNotEmpty() }
                    .map { DevicePub(it.id, it.pub_key) }
                // Include our OWN other devices so multi-device self can decrypt too.
                val myDevices = runCatching { api.myDevices() }.getOrDefault(emptyList())
                    .filter { it.pub_key.isNotEmpty() }
                    .map { DevicePub(it.id, it.pub_key) }
                val targets = (peerDevices + myDevices).distinctBy { it.deviceId }
                if (targets.isEmpty()) return@runCatching false // peer has no registered device keys
                val envelopesToSend = manager.wrapDmKeyFor(channelId, targets)
                if (envelopesToSend.isNotEmpty()) api.postKeyEnvelopes(channelId, envelopesToSend)
                true
            }.getOrDefault(false)
            if (ok) establishedSessions.add(channelId)
            ok
        }
    }

    private suspend fun decryptForDisplay(channelId: String, content: String, isEncrypted: Boolean): String {
        val manager = e2ee ?: return content
        if (!isEncrypted || content.isEmpty()) return content
        // First-encrypted-message-in receive path: if we haven't established the session
        // for this DM yet, try now (unwraps the peer's envelope + seeds the ratchet).
        // Best-effort — on failure decrypt below just yields the locked placeholder.
        if (channelId !in establishedSessions) {
            runCatching { ensureE2eeSession(channelId, manager) }
        }
        return runCatching { manager.decrypt(channelId, content) }.getOrNull() ?: lockedPlaceholder
    }

    private suspend fun mergeEvent(dto: MessageDto) {
        // Decrypt BEFORE opening the transaction: for an encrypted row this may run the
        // key exchange (network), which must not happen while holding the DB write lock.
        // The server's `encrypted` flag drives it — plaintext rows are a no-op.
        val display = decryptForDisplay(dto.channel_id, dto.content, isEncrypted = dto.encrypted)
        mergeEventDecrypted(dto, display)
    }

    private suspend fun mergeEventDecrypted(dto: MessageDto, display: String) = db.withTransaction {
        val incoming = dto.toEntity().let { if (display !== dto.content) it.copy(content = display) else it }
        // Reconcile with our own optimistic send: the gateway echo (and the send
        // API response) carry the nonce we minted locally. The optimistic row was
        // inserted under a temporary id ("pending-<nonce>"), so a lookup by the
        // server id misses it and we'd otherwise keep a second, stuck "sending" row.
        // Drop the placeholder (and any stale row still holding this nonce under a
        // different id) before writing the authoritative copy.
        dto.nonce?.let { nonce ->
            db.messages().delete("pending-$nonce")
            db.messages().byNonce(nonce)?.let { existing ->
                if (existing.id != incoming.id) db.messages().delete(existing.id)
            }
        }
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
