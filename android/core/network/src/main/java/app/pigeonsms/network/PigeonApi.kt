package app.pigeonsms.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val PIGEON_BASE = "https://api.pigeonsms.aldi.best"
const val PIGEON_WS = "wss://api.pigeonsms.aldi.best/gateway"

class PigeonApiException(val code: String, override val message: String) : Exception(message)

/** Query values (search text, filenames) can hold spaces/&/# — encode or the URL breaks. */
private fun q(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

class PigeonApi(
    val baseUrl: String = PIGEON_BASE,
    private val tokenProvider: suspend () -> String?,
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@PigeonApi.json) }
        install(WebSockets)
        expectSuccess = false
    }

    private suspend fun HttpRequestBuilder.auth() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend inline fun <reified T> HttpResponse.unwrap(): T {
        if (status.isSuccess()) return body()
        val detail = runCatching { json.decodeFromString<ErrorEnvelope>(bodyAsText()).error }.getOrNull()
        throw PigeonApiException(detail?.code ?: "http_${status.value}", detail?.message ?: "something went wrong")
    }

    // --- auth ---
    suspend fun checkInvite(code: String) = client.get("$baseUrl/auth/invite/$code").unwrap<InviteCheckResponse>().valid

    suspend fun signup(invite: String, username: String, email: String, password: String, deviceName: String) =
        client.post("$baseUrl/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("invite", invite); put("username", username); put("email", email)
                put("password", password); put("device_name", deviceName)
            })
        }.unwrap<AuthResponse>()

    suspend fun login(login: String, password: String, deviceName: String, totp: String? = null) =
        client.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("login", login); put("password", password); put("device_name", deviceName)
                if (totp != null) put("totp", totp)
            })
        }.unwrap<AuthResponse>()

    suspend fun me() = client.get("$baseUrl/auth/me") { auth() }.unwrap<MeResponse>().user
    suspend fun logout() { client.post("$baseUrl/auth/logout") { auth() }.unwrap<OkResponse>() }
    suspend fun sessions() = client.get("$baseUrl/auth/sessions") { auth() }.unwrap<SessionsResponse>().sessions
    suspend fun revokeSession(id: String) { client.delete("$baseUrl/auth/sessions/$id") { auth() }.unwrap<OkResponse>() }
    suspend fun history() = client.get("$baseUrl/auth/history") { auth() }.unwrap<HistoryResponse>().history

    // --- security ---
    suspend fun totpSetup() = client.post("$baseUrl/auth/totp/setup") { auth() }.unwrap<TotpSetupResponse>()
    suspend fun totpEnable(code: String) = client.post("$baseUrl/auth/totp/enable") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("code", code) })
    }.unwrap<RecoveryResponse>().recovery_codes
    suspend fun totpDisable(code: String) { client.post("$baseUrl/auth/totp/disable") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("code", code) })
    }.unwrap<OkResponse>() }
    suspend fun exportData(): String {
        val r = client.get("$baseUrl/auth/export") { auth() }
        if (!r.status.isSuccess()) throw PigeonApiException("export_failed", "export failed")
        return r.bodyAsText()
    }
    suspend fun deleteAccount(password: String) { client.delete("$baseUrl/auth/me") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("password", password) })
    }.unwrap<OkResponse>() }
    /** Trusted-user only (admin / a_arond / andrei); server 403s otherwise. count = codes to mint, uses = max uses each. */
    suspend fun generateInvites(count: Int, uses: Int) = client.post("$baseUrl/auth/invites") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("count", count); put("uses", uses) })
    }.unwrap<GenerateInvitesResponse>().invites

    // --- friends ---
    suspend fun friends() = client.get("$baseUrl/friends") { auth() }.unwrap<FriendsResponse>()
    suspend fun addFriend(username: String) { client.post("$baseUrl/friends/requests") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("username", username) })
    }.unwrap<JsonObject>() }
    suspend fun acceptFriend(userId: String) { client.post("$baseUrl/friends/$userId/accept") { auth() }.unwrap<OkResponse>() }
    suspend fun removeFriend(userId: String) { client.delete("$baseUrl/friends/$userId") { auth() }.unwrap<OkResponse>() }
    suspend fun updateFriend(userId: String, note: String?, closeFriend: Boolean?) { client.patch("$baseUrl/friends/$userId") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject { if (note != null) put("note", note); if (closeFriend != null) put("close_friend", closeFriend) })
    }.unwrap<OkResponse>() }
    suspend fun block(userId: String) { client.post("$baseUrl/friends/blocks/$userId") { auth() }.unwrap<OkResponse>() }
    suspend fun unblock(userId: String) { client.delete("$baseUrl/friends/blocks/$userId") { auth() }.unwrap<OkResponse>() }
    suspend fun blocks() = client.get("$baseUrl/friends/blocks") { auth() }.unwrap<BlocksResponse>().blocks

    // --- dms ---
    suspend fun dms() = client.get("$baseUrl/dms") { auth() }.unwrap<DmsResponse>().dms
    suspend fun openDm(userId: String) = client.post("$baseUrl/dms/open") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("user_id", userId) })
    }.unwrap<OpenDmResponse>().channel_id

    // --- messages ---
    suspend fun messagesPage(channelId: String, before: Long? = null) =
        client.get("$baseUrl/channels/$channelId/messages${if (before != null) "?before=$before" else ""}") { auth() }
            .unwrap<MessagesResponse>()

    suspend fun messages(channelId: String, before: Long? = null) = messagesPage(channelId, before).messages

    /**
     * The channel's global last_seq (forum replies included) — cheap probe used to
     * clear forum unread badges. Best-effort like markRead: 0 when unavailable.
     */
    suspend fun channelLastSeq(channelId: String): Long = runCatching {
        client.get("$baseUrl/channels/$channelId/messages?limit=1") { auth() }
            .unwrap<MessagesResponse>().cursor?.channel_last_seq
    }.getOrNull() ?: 0L

    /**
     * ttl (seconds) makes the message disappearing (server sets expires_at = now + ttl*1000).
     * sendAt (epoch ms) in the future schedules it instead of sending (goes to scheduled_messages).
     * encrypted=true stores base64 ciphertext in content, encrypted=1 server-side (E2EE, flag-off).
     *
     * Returns the full [SendResponse]: a normal send carries `message`, a future send_at
     * carries `scheduled` (HTTP 202, no `message`). Callers must handle the scheduled
     * case — unwrapping `.message!!` on a scheduled response would NPE / mis-fail.
     */
    suspend fun sendMessage(
        channelId: String,
        content: String,
        nonce: String,
        replyTo: String? = null,
        attachment: AttachmentDto? = null,
        ttl: Long? = null,
        sendAt: Long? = null,
        encrypted: Boolean = false,
    ): SendResponse =
        client.post("$baseUrl/channels/$channelId/messages") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("content", content); put("nonce", nonce)
                if (replyTo != null) put("reply_to", replyTo)
                if (attachment != null) put("attachment", json.encodeToJsonElement(AttachmentDto.serializer(), attachment))
                if (ttl != null) put("ttl", ttl)
                if (sendAt != null) put("send_at", sendAt)
                if (encrypted) put("encrypted", 1)
            })
        }.unwrap<SendResponse>()

    /**
     * Poll message: kind="poll" + poll {question, options[2..10], anonymous}.
     * The server is single-choice only (multiple_choice: true is rejected) and
     * uses the question as content when content is blank.
     */
    suspend fun sendPoll(channelId: String, question: String, options: List<String>, anonymous: Boolean, nonce: String) =
        client.post("$baseUrl/channels/$channelId/messages") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("content", question); put("nonce", nonce); put("kind", "poll")
                putJsonObject("poll") {
                    put("question", question)
                    putJsonArray("options") { options.forEach { add(it) } }
                    put("anonymous", anonymous)
                }
            })
        }.unwrap<MessageResponse>().message

    /** Event message: kind="event" + metadata {title, starts_at, ends_at?, location?, description?} (epoch ms). */
    suspend fun sendEvent(
        channelId: String,
        title: String,
        startsAt: Long,
        endsAt: Long?,
        location: String?,
        description: String?,
        nonce: String,
    ) = client.post("$baseUrl/channels/$channelId/messages") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("content", title); put("nonce", nonce); put("kind", "event")
            putJsonObject("metadata") {
                put("title", title); put("starts_at", startsAt)
                if (endsAt != null) put("ends_at", endsAt)
                if (!location.isNullOrBlank()) put("location", location)
                if (!description.isNullOrBlank()) put("description", description)
            }
        })
    }.unwrap<MessageResponse>().message

    /** One choice per user; voting again moves the vote. */
    suspend fun votePoll(messageId: String, optionId: String) =
        client.put("$baseUrl/messages/$messageId/poll/votes/$optionId") { auth() }.unwrap<PollVoteResponse>()
    suspend fun retractPollVote(messageId: String) =
        client.delete("$baseUrl/messages/$messageId/poll/vote") { auth() }.unwrap<PollVoteResponse>()

    suspend fun editMessage(id: String, content: String) = client.patch("$baseUrl/messages/$id") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("content", content) })
    }.unwrap<MessageResponse>().message
    suspend fun deleteMessage(id: String) { client.delete("$baseUrl/messages/$id") { auth() }.unwrap<OkResponse>() }
    suspend fun addReaction(id: String, emoji: String) =
        client.put("$baseUrl/messages/$id/reactions/$emoji") { auth() }.unwrap<ReactionMutationResponse>()
    suspend fun removeReaction(id: String, emoji: String) =
        client.delete("$baseUrl/messages/$id/reactions/$emoji") { auth() }.unwrap<ReactionMutationResponse>()
    suspend fun pin(id: String) { client.put("$baseUrl/messages/$id/pin") { auth() }.unwrap<OkResponse>() }
    suspend fun unpin(id: String) { client.delete("$baseUrl/messages/$id/pin") { auth() }.unwrap<OkResponse>() }
    suspend fun likeMessage(id: String) = client.put("$baseUrl/messages/$id/like") { auth() }.unwrap<LikeMutationResponse>()
    suspend fun unlikeMessage(id: String) = client.delete("$baseUrl/messages/$id/like") { auth() }.unwrap<LikeMutationResponse>()
    /** Toggle "mark" (e.g. answer/resolved). 400 not_markable when the channel has no mark tag. */
    suspend fun markMessage(id: String) = client.put("$baseUrl/messages/$id/marked") { auth() }.unwrap<MarkMutationResponse>()
    suspend fun unmarkMessage(id: String) = client.delete("$baseUrl/messages/$id/marked") { auth() }.unwrap<MarkMutationResponse>()
    suspend fun pins(channelId: String) = client.get("$baseUrl/channels/$channelId/pins") { auth() }.unwrap<MessagesResponse>().messages
    suspend fun superPin(channelId: String) = client.get("$baseUrl/channels/$channelId/super-pin") { auth() }.unwrap<SuperPinResponse>().super_pin
    suspend fun setSuperPin(messageId: String) = client.put("$baseUrl/messages/$messageId/super-pin") { auth() }.unwrap<SuperPinResponse>().super_pin
    suspend fun removeSuperPin(channelId: String) { client.delete("$baseUrl/channels/$channelId/super-pin") { auth() }.unwrap<OkResponse>() }
    suspend fun dismissSuperPin(channelId: String) { client.put("$baseUrl/channels/$channelId/super-pin/dismiss") { auth() }.unwrap<OkResponse>() }
    suspend fun search(channelId: String, q: String) = client.get("$baseUrl/channels/$channelId/search?q=${q(q)}") { auth() }.unwrap<MessagesResponse>().messages
    /** Space-wide FTS5 search across permitted channels; paginate with before (LIMIT 50). Skips encrypted messages server-side. */
    suspend fun searchSpace(spaceId: String, q: String, before: Long? = null) =
        client.get("$baseUrl/spaces/$spaceId/search?q=${q(q)}${if (before != null) "&before=$before" else ""}") { auth() }
            .unwrap<SearchResponse>()
    suspend fun typing(channelId: String) { runCatching { client.post("$baseUrl/channels/$channelId/typing") { auth() } } }
    suspend fun markRead(channelId: String, seq: Long) { runCatching {
        client.put("$baseUrl/channels/$channelId/read") { auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("seq", seq) }) }
    } }

    // --- notification preferences ---
    suspend fun notificationPreferences() = client.get("$baseUrl/notifications/preferences") { auth() }
        .unwrap<NotificationPreferencesResponse>()
    suspend fun setNotificationPreference(
        scopeType: String,
        scopeId: String = "",
        mode: String = "all",
        sound: Boolean = true,
        vibration: Boolean = true,
        badge: Boolean = true,
    ) = client.put("$baseUrl/notifications/preferences") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("scope_type", scopeType); put("scope_id", scopeId); put("mode", mode)
            put("sound", sound); put("vibration", vibration); put("badge", badge)
        })
    }.unwrap<OkResponse>()
    suspend fun resetNotificationPreference(scopeType: String, scopeId: String = "") = client.delete(
        "$baseUrl/notifications/preferences?scope_type=${q(scopeType)}&scope_id=${q(scopeId)}",
    ) { auth() }.unwrap<OkResponse>()

    // --- spaces ---
    suspend fun spaces() = client.get("$baseUrl/spaces") { auth() }.unwrap<SpacesResponse>().spaces
    /** Without a nonce the server's legacy name-match silently returns an existing same-name space. */
    suspend fun createSpace(name: String, nonce: String) = client.post("$baseUrl/spaces") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("name", name); put("nonce", nonce) })
    }.unwrap<CreateSpaceResponse>().space
    /** kind is one of "text", "voice", "forum" — anything else is a 400. */
    suspend fun createChannel(spaceId: String, name: String, kind: String = "text") = client.post("$baseUrl/spaces/$spaceId/channels") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("name", name); put("kind", kind) })
    }.unwrap<CreateChannelResponse>().channel
    /** Owner-only rename. Fanout `channel.update {id,space_id,name,topic,kind}`. */
    suspend fun renameChannel(spaceId: String, channelId: String, name: String) = client.patch("$baseUrl/spaces/$spaceId/channels/$channelId") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("name", name) })
    }.unwrap<CreateChannelResponse>().channel
    /** Owner-only delete. Fanout `channel.delete {id,space_id}`. */
    suspend fun deleteChannel(spaceId: String, channelId: String) { client.delete("$baseUrl/spaces/$spaceId/channels/$channelId") { auth() }.unwrap<OkResponse>() }
    suspend fun spaceInvite(spaceId: String) = client.post("$baseUrl/spaces/$spaceId/invites") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { })
    }.unwrap<SpaceInviteResponse>()
    suspend fun joinSpace(code: String) = client.post("$baseUrl/spaces/join") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("code", code) })
    }.unwrap<JoinSpaceResponse>().space_id
    suspend fun spaceMembers(spaceId: String) = client.get("$baseUrl/spaces/$spaceId/members") { auth() }.unwrap<MembersResponse>().members
    suspend fun setRole(spaceId: String, userId: String, role: String) { client.put("$baseUrl/spaces/$spaceId/members/$userId/role") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("role", role) })
    }.unwrap<OkResponse>() }
    suspend fun transferSpace(spaceId: String, userId: String) { client.post("$baseUrl/spaces/$spaceId/transfer") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("user_id", userId) })
    }.unwrap<OkResponse>() }
    suspend fun setSpaceIcon(spaceId: String, key: String?) = client.patch("$baseUrl/spaces/$spaceId/icon") {
        auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("key", key) })
    }.unwrap<SpaceResponse>().space
    suspend fun leaveSpace(spaceId: String) { client.delete("$baseUrl/spaces/$spaceId/members/me") { auth() }.unwrap<OkResponse>() }
    suspend fun deleteSpace(spaceId: String) { client.delete("$baseUrl/spaces/$spaceId") { auth() }.unwrap<OkResponse>() }

    // --- forums (forum-kind channels only; the plain message endpoints 400 there) ---
    /** sort is one of "active" (default), "recent", "oldest". tag filters by tag id or name. */
    suspend fun forumPosts(channelId: String, sort: String = "active", tag: String? = null) =
        client.get(
            "$baseUrl/channels/$channelId/forum/posts?sort=${q(sort)}${if (tag != null) "&tag=${q(tag)}" else ""}",
        ) { auth() }.unwrap<ForumPostsResponse>().posts

    /** Owner-only tag definition. mark_label, when set, turns the tag into a "mark" (e.g. resolved). */
    suspend fun createForumTag(channelId: String, name: String, markLabel: String? = null) =
        client.post("$baseUrl/channels/$channelId/forum/tags") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name); if (markLabel != null) put("mark_label", markLabel) })
        }.unwrap<ForumTagResponse>().tag

    suspend fun forumTags(channelId: String) =
        client.get("$baseUrl/channels/$channelId/forum/tags") { auth() }.unwrap<ForumTagsResponse>().tags

    suspend fun forumThread(channelId: String, postId: String, after: Long? = null) =
        client.get("$baseUrl/channels/$channelId/forum/posts/$postId${if (after != null) "?after=$after" else ""}") { auth() }
            .unwrap<ForumThreadResponse>()

    /** Creates a kind="forum_post" message; the title lands in metadata.title. tag is a tag id. */
    suspend fun createForumPost(
        channelId: String,
        title: String,
        content: String = "",
        nonce: String,
        attachment: AttachmentDto? = null,
        tag: String? = null,
    ) = client.post("$baseUrl/channels/$channelId/forum/posts") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("title", title); put("nonce", nonce)
            if (content.isNotBlank()) put("content", content)
            if (tag != null) put("tag", tag)
            if (attachment != null) put("attachment", json.encodeToJsonElement(AttachmentDto.serializer(), attachment))
        })
    }.unwrap<MessageResponse>().message

    /** Creates a kind="forum_reply" in the post's thread; replyTo targets a message inside that thread. */
    suspend fun createForumReply(channelId: String, postId: String, content: String, nonce: String, replyTo: String? = null, attachment: AttachmentDto? = null) =
        client.post("$baseUrl/channels/$channelId/forum/posts/$postId/replies") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("content", content); put("nonce", nonce)
                if (replyTo != null) put("reply_to", replyTo)
                if (attachment != null) put("attachment", json.encodeToJsonElement(AttachmentDto.serializer(), attachment))
            })
        }.unwrap<MessageResponse>().message

    // --- users / profile ---
    suspend fun searchUsers(q: String) = client.get("$baseUrl/users/search?q=${q(q)}") { auth() }.unwrap<UsersSearchResponse>().users
    suspend fun profile(userId: String) = client.get("$baseUrl/users/$userId/profile") { auth() }.unwrap<ProfileResponse>()
    suspend fun updateProfile(fields: Map<String, String?>) { client.patch("$baseUrl/users/me") {
        auth(); contentType(ContentType.Application.Json)
        setBody(JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
    }.unwrap<OkResponse>() }

    // --- media ---
    suspend fun uploadFile(bytes: ByteArray, filename: String, type: String) =
        client.post("$baseUrl/media/upload?filename=${q(filename)}&type=${q(type)}") {
            auth(); setBody(bytes)
        }.unwrap<UploadResponse>().attachment
    suspend fun uploadAvatar(bytes: ByteArray, type: String) = client.post("$baseUrl/media/avatar") {
        auth(); contentType(ContentType.parse(type)); setBody(bytes)
    }.unwrap<AvatarResponse>().avatar_key
    suspend fun uploadBanner(bytes: ByteArray, type: String) = client.post("$baseUrl/media/banner") {
        auth(); contentType(ContentType.parse(type)); setBody(bytes)
    }.unwrap<BannerResponse>().key
    suspend fun resetAvatar() { client.delete("$baseUrl/media/avatar") { auth() }.unwrap<OkResponse>() }
    suspend fun resetBanner() { client.delete("$baseUrl/media/banner") { auth() }.unwrap<OkResponse>() }
    fun mediaUrl(key: String) = "$baseUrl/media/$key"

    // --- push / updates ---
    suspend fun registerPush(token: String) {
        client.post("$baseUrl/push/tokens") {
            auth(); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("token", token) })
        }
    }
    suspend fun latestRelease() = client.get("$baseUrl/updates/latest").unwrap<LatestReleaseResponse>().release
    /** Admin-only: re-broadcast the update FCM to every push token for an already-published release. */
    suspend fun notifyAllOfRelease(versionCode: Int) {
        client.post("$baseUrl/admin/releases/$versionCode/notify") { auth() }.unwrap<OkResponse>()
    }

    // --- devices / e2ee (ships flag-off, experimental) ---
    /** Register this device's X25519 identity pub key. Returns the new device id. */
    suspend fun postDevice(pubKey: String, name: String? = null): String {
        val body = client.post("$baseUrl/auth/devices") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("pub_key", pubKey); if (name != null) put("name", name) })
        }.unwrap<JsonObject>()
        return (body["id"] as? JsonPrimitive)?.content ?: ""
    }
    /** The caller's own registered devices (full detail). */
    suspend fun myDevices() = client.get("$baseUrl/auth/devices") { auth() }.unwrap<DevicesResponse>().devices
    /** Another user's device pub keys — only when a mutual DM/friend exists (403 otherwise). */
    suspend fun userDevices(userId: String) = client.get("$baseUrl/users/$userId/devices") { auth() }.unwrap<DevicesResponse>().devices
    /** Password-derived encrypted key backup for multi-device recovery; null when none stored. */
    suspend fun getKeyBackup() = client.get("$baseUrl/auth/key-backup") { auth() }.unwrap<KeyBackupResponse>().backup
    suspend fun putKeyBackup(blob: String, salt: String, params: String) {
        client.put("$baseUrl/auth/key-backup") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("blob", blob); put("kdf_salt", salt); put("kdf_params", params) })
        }.unwrap<OkResponse>()
    }
    /** Deliver per-DM symmetric keys wrapped (sealed box) to each recipient device. */
    suspend fun postKeyEnvelopes(channelId: String, list: List<KeyEnvelopeDto>) {
        client.post("$baseUrl/channels/$channelId/key-envelopes") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("envelopes") {
                    list.forEach { add(buildJsonObject { put("to_device", it.to_device); put("wrapped_key", it.wrapped_key) }) }
                }
            })
        }.unwrap<OkResponse>()
    }
    /** Wrapped keys addressed to the caller's own devices for this channel. */
    suspend fun getKeyEnvelopes(channelId: String) =
        client.get("$baseUrl/channels/$channelId/key-envelopes") { auth() }.unwrap<EnvelopesResponse>().envelopes

    // --- scheduled messages ---
    suspend fun listScheduled() = client.get("$baseUrl/scheduled") { auth() }.unwrap<ScheduledResponse>().scheduled
    suspend fun cancelScheduled(id: String) { client.delete("$baseUrl/scheduled/$id") { auth() }.unwrap<OkResponse>() }
}
