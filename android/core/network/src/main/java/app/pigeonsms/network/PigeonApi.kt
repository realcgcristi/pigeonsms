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
import kotlinx.serialization.json.buildJsonArray
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

    suspend fun callConfig(channelId: String) =
        client.get("$baseUrl/calls/$channelId/config") { auth() }.unwrap<CallConfigResponse>()

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

    suspend fun passkeyRegistrationOptions() = client.post("$baseUrl/auth/passkeys/register/options") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("platform", "android") })
    }.unwrap<PasskeyOptionsResponse>()

    suspend fun verifyPasskeyRegistration(challengeId: String, response: JsonObject, name: String) =
        client.post("$baseUrl/auth/passkeys/register/verify") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("challenge_id", challengeId)
                put("response", response)
                put("name", name)
            })
        }.unwrap<PasskeyResponse>().passkey

    suspend fun passkeyAuthenticationOptions(login: String? = null) =
        client.post("$baseUrl/auth/passkeys/authenticate/options") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("platform", "android")
                if (!login.isNullOrBlank()) put("login", login.trim())
            })
        }.unwrap<PasskeyOptionsResponse>()

    suspend fun verifyPasskeyAuthentication(challengeId: String, response: JsonObject, deviceName: String) =
        client.post("$baseUrl/auth/passkeys/authenticate/verify") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("challenge_id", challengeId)
                put("response", response)
                put("device_name", deviceName)
            })
        }.unwrap<AuthResponse>()

    suspend fun passkeys() = client.get("$baseUrl/auth/passkeys") { auth() }.unwrap<PasskeysResponse>().passkeys

    suspend fun revokePasskey(id: String) {
        client.delete("$baseUrl/auth/passkeys/$id") { auth() }.unwrap<OkResponse>()
    }

    suspend fun createPairing() = client.post("$baseUrl/auth/pairings") { auth() }
        .unwrap<PairingInviteResponse>().pairing

    suspend fun pairings() = client.get("$baseUrl/auth/pairings") { auth() }
        .unwrap<PairingsResponse>().pairings

    suspend fun pairing(id: String) = client.get("$baseUrl/auth/pairings/$id") { auth() }
        .unwrap<PairingResponse>().pairing

    suspend fun requestPairing(id: String, secret: String, claimSecret: String, deviceName: String) =
        client.post("$baseUrl/auth/pairings/$id/request") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("secret", secret)
                put("claim_secret", claimSecret)
                put("device_name", deviceName)
            })
        }.unwrap<PairingResponse>().pairing

    suspend fun pairingStatus(id: String, secret: String, claimSecret: String) =
        client.post("$baseUrl/auth/pairings/$id/status") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("secret", secret)
                put("claim_secret", claimSecret)
            })
        }.unwrap<PairingResponse>().pairing

    suspend fun approvePairing(id: String) {
        client.post("$baseUrl/auth/pairings/$id/approve") { auth() }.unwrap<OkResponse>()
    }

    suspend fun denyPairing(id: String) {
        client.post("$baseUrl/auth/pairings/$id/deny") { auth() }.unwrap<OkResponse>()
    }

    suspend fun cancelPairing(id: String) {
        client.delete("$baseUrl/auth/pairings/$id") { auth() }.unwrap<OkResponse>()
    }

    suspend fun claimPairing(id: String, secret: String, claimSecret: String) =
        client.post("$baseUrl/auth/pairings/$id/claim") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("secret", secret)
                put("claim_secret", claimSecret)
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
    suspend fun nestShield(spaceId: String) = client.get("$baseUrl/spaces/$spaceId/shield") { auth() }.unwrap<NestShieldResponse>()
    suspend fun updateNestShield(spaceId: String, settings: NestShieldSettingsDto) =
        client.put("$baseUrl/spaces/$spaceId/shield") {
            auth(); contentType(ContentType.Application.Json)
            setBody(json.encodeToJsonElement(NestShieldSettingsDto.serializer(), settings))
        }.unwrap<NestShieldResponse>().settings
    suspend fun updateChannelShield(spaceId: String, channelId: String, seconds: Int) {
        client.put("$baseUrl/spaces/$spaceId/shield/channels/$channelId") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("slowmode_seconds", seconds) })
        }.unwrap<OkResponse>()
    }
    suspend fun shieldActions(spaceId: String) = client.get("$baseUrl/spaces/$spaceId/shield/actions") { auth() }
        .unwrap<ShieldActionsResponse>().actions
    suspend fun memberTimeouts(spaceId: String) = client.get("$baseUrl/spaces/$spaceId/timeouts") { auth() }
        .unwrap<MemberTimeoutsResponse>().timeouts
    suspend fun timeoutMember(spaceId: String, userId: String, duration: Int, reason: String?) =
        client.put("$baseUrl/spaces/$spaceId/timeouts/$userId") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("duration_seconds", duration)
                if (!reason.isNullOrBlank()) put("reason", reason)
            })
        }.unwrap<MemberTimeoutResponse>().timeout
    suspend fun clearMemberTimeout(spaceId: String, userId: String) {
        client.delete("$baseUrl/spaces/$spaceId/timeouts/$userId") { auth() }.unwrap<OkResponse>()
    }
    suspend fun moderationReports(spaceId: String) = client.get("$baseUrl/spaces/$spaceId/reports?status=open") { auth() }
        .unwrap<ModerationReportsResponse>().reports
    suspend fun resolveModerationReport(spaceId: String, reportId: String, status: String) {
        client.patch("$baseUrl/spaces/$spaceId/reports/$reportId") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("status", status) })
        }.unwrap<OkResponse>()
    }
    suspend fun reportMessage(messageId: String, category: String, reason: String?) =
        client.post("$baseUrl/messages/$messageId/report") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("category", category)
                if (!reason.isNullOrBlank()) put("reason", reason)
            })
        }.unwrap<ModerationReportResponse>().report
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

    suspend fun exportSpaceMigration(spaceId: String) =
        client.get("$baseUrl/spaces/$spaceId/migration") { auth() }.unwrap<MigrationExportResponse>()

    suspend fun importSpaceMigration(bundle: JsonObject, name: String? = null, force: Boolean = false) =
        client.post("$baseUrl/spaces/migrate") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("bundle", bundle)
                put("force", force)
                if (!name.isNullOrBlank()) put("name", name)
            })
        }.unwrap<MigrationImportResponse>()

    suspend fun timeEvents(spaceId: String, after: Long = 0, limit: Int = 500) =
        client.get("$baseUrl/spaces/$spaceId/time-machine/events?after=$after&limit=$limit") { auth() }
            .unwrap<TimeEventsResponse>()

    suspend fun timeCapsules(spaceId: String) =
        client.get("$baseUrl/spaces/$spaceId/time-machine/capsules") { auth() }
            .unwrap<TimeCapsulesResponse>().capsules

    suspend fun createTimeCapsule(
        spaceId: String,
        name: String,
        ciphertext: String,
        iv: String,
        salt: String,
        kdf: String,
    ) = client.post("$baseUrl/spaces/$spaceId/time-machine/capsules") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("name", name); put("ciphertext", ciphertext); put("iv", iv)
            put("salt", salt); put("kdf", kdf)
        })
    }.unwrap<TimeCapsuleResponse>().capsule

    suspend fun timeCapsule(spaceId: String, capsuleId: String) =
        client.get("$baseUrl/spaces/$spaceId/time-machine/capsules/$capsuleId") { auth() }
            .unwrap<TimeCapsuleResponse>().capsule

    suspend fun deleteTimeCapsule(spaceId: String, capsuleId: String) {
        client.delete("$baseUrl/spaces/$spaceId/time-machine/capsules/$capsuleId") { auth() }.unwrap<OkResponse>()
    }

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
    suspend fun downloadMedia(key: String) = client.get("$baseUrl/media/${q(key)}") { auth() }.unwrap<ByteArray>()
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
    suspend fun pendingDeviceSync() = client.get("$baseUrl/auth/device-sync") { auth() }.unwrap<DevicesResponse>().devices
    suspend fun completeDeviceSync(id: String) {
        client.delete("$baseUrl/auth/device-sync/${q(id)}") { auth() }.unwrap<OkResponse>()
    }
    /** Another user's device pub keys — only when a mutual DM/friend exists (403 otherwise). */
    suspend fun userDevices(userId: String) = client.get("$baseUrl/users/$userId/devices") { auth() }.unwrap<DevicesResponse>().devices
    suspend fun transparency(userId: String) =
        client.get("$baseUrl/transparency/$userId").unwrap<TransparencyResponse>()

    suspend fun gossipTransparency(userId: String, checkpoint: TransparencyCheckpointDto) =
        client.post("$baseUrl/transparency/$userId/gossip") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("tree_size", checkpoint.tree_size)
                put("root_hash", checkpoint.root_hash)
            })
        }.unwrap<TransparencyGossipResponse>()
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
                list.firstOrNull()?.key_id?.let { put("key_id", it) }
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

    // --- v2.9.5: custom emoji + stickers ---
    suspend fun spaceEmojis(spaceId: String) =
        client.get("$baseUrl/spaces/$spaceId/emojis") { auth() }.unwrap<SpaceEmojisResponse>().emojis

    /** Register an already-uploaded image as a nest emoji or sticker. */
    suspend fun createSpaceEmoji(
        spaceId: String,
        name: String,
        mediaKey: String,
        kind: String = "emoji",
        contentType: String? = null,
    ) = client.post("$baseUrl/spaces/$spaceId/emojis") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("name", name); put("media_key", mediaKey); put("kind", kind)
            if (contentType != null) put("content_type", contentType)
        })
    }.unwrap<SpaceEmojiResponse>().emoji

    suspend fun renameSpaceEmoji(spaceId: String, emojiId: String, name: String) =
        client.patch("$baseUrl/spaces/$spaceId/emojis/$emojiId") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) })
        }.unwrap<SpaceEmojiResponse>().emoji

    suspend fun deleteSpaceEmoji(spaceId: String, emojiId: String) {
        client.delete("$baseUrl/spaces/$spaceId/emojis/$emojiId") { auth() }.unwrap<OkResponse>()
    }

    // --- v2.9.5: roles + permissions ---
    suspend fun spaceRoles(spaceId: String) =
        client.get("$baseUrl/spaces/$spaceId/roles") { auth() }.unwrap<SpaceRolesResponse>().roles

    /** What the caller may do here; pass [channelId] to include channel overrides. */
    suspend fun spacePermissions(spaceId: String, channelId: String? = null) =
        client.get(
            "$baseUrl/spaces/$spaceId/permissions" + (channelId?.let { "?channel_id=$it" } ?: ""),
        ) { auth() }.unwrap<PermissionsResponse>()

    suspend fun createRole(spaceId: String, name: String, permissions: List<String>, color: String? = null) =
        client.post("$baseUrl/spaces/$spaceId/roles") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", name)
                if (color != null) put("color", color)
                put("permissions", buildJsonArray { permissions.forEach { add(it) } })
            })
        }.unwrap<SpaceRoleResponse>().role

    suspend fun updateRole(
        spaceId: String,
        roleId: String,
        name: String? = null,
        permissions: List<String>? = null,
        color: String? = null,
        position: Int? = null,
    ) = client.patch("$baseUrl/spaces/$spaceId/roles/$roleId") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            if (name != null) put("name", name)
            if (color != null) put("color", color)
            if (position != null) put("position", position)
            if (permissions != null) put("permissions", buildJsonArray { permissions.forEach { add(it) } })
        })
    }.unwrap<SpaceRoleResponse>().role

    suspend fun deleteRole(spaceId: String, roleId: String) {
        client.delete("$baseUrl/spaces/$spaceId/roles/$roleId") { auth() }.unwrap<OkResponse>()
    }

    suspend fun setMemberRoles(spaceId: String, userId: String, roleIds: List<String>) {
        client.put("$baseUrl/spaces/$spaceId/members/$userId/roles") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("role_ids", buildJsonArray { roleIds.forEach { add(it) } }) })
        }.unwrap<OkResponse>()
    }

    suspend fun channelOverrides(spaceId: String, channelId: String) =
        client.get("$baseUrl/spaces/$spaceId/channels/$channelId/overrides") { auth() }
            .unwrap<ChannelOverridesResponse>().overrides

    suspend fun setChannelOverride(
        spaceId: String,
        channelId: String,
        roleId: String? = null,
        userId: String? = null,
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList(),
    ) {
        client.put("$baseUrl/spaces/$spaceId/channels/$channelId/overrides") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                if (roleId != null) put("role_id", roleId)
                if (userId != null) put("user_id", userId)
                put("allow", buildJsonArray { allow.forEach { add(it) } })
                put("deny", buildJsonArray { deny.forEach { add(it) } })
            })
        }.unwrap<OkResponse>()
    }

    // --- v2.9.5: threads ---
    suspend fun channelThreads(channelId: String, archived: Boolean = false) =
        client.get("$baseUrl/channels/$channelId/threads?archived=${if (archived) 1 else 0}") { auth() }
            .unwrap<ThreadsResponse>().threads

    suspend fun createThread(channelId: String, messageId: String, title: String? = null) =
        client.post("$baseUrl/channels/$channelId/threads") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("message_id", messageId)
                if (title != null) put("title", title)
            })
        }.unwrap<ThreadResponse>().thread

    suspend fun thread(threadId: String) =
        client.get("$baseUrl/threads/$threadId") { auth() }.unwrap<ThreadResponse>()

    suspend fun threadMessages(threadId: String, before: Long? = null) =
        client.get("$baseUrl/threads/$threadId/messages" + (before?.let { "?before=$it" } ?: "")) { auth() }
            .unwrap<ThreadMessagesResponse>()

    suspend fun sendThreadMessage(threadId: String, content: String, nonce: String? = null) =
        client.post("$baseUrl/threads/$threadId/messages") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("content", content)
                if (nonce != null) put("nonce", nonce)
            })
        }.unwrap<MessageResponse>().message

    suspend fun updateThread(threadId: String, title: String? = null, archived: Boolean? = null) =
        client.patch("$baseUrl/threads/$threadId") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                if (title != null) put("title", title)
                if (archived != null) put("archived", archived)
            })
        }.unwrap<ThreadResponse>().thread

    suspend fun followThread(threadId: String, follow: Boolean) {
        if (follow) client.post("$baseUrl/threads/$threadId/follow") { auth() }.unwrap<OkResponse>()
        else client.delete("$baseUrl/threads/$threadId/follow") { auth() }.unwrap<OkResponse>()
    }


    // --- v2.9.5: global search (across every nest + DMs) ---
    suspend fun searchEverywhere(query: String, before: Long? = null) =
        client.get("$baseUrl/search?q=${q(query)}" + (before?.let { "&before=$it" } ?: "")) { auth() }
            .unwrap<SearchResponse>()

    // --- v2.9.5: resumable uploads ---
    /** Open a multipart session. Returns the part size the server expects. */
    suspend fun openUpload(filename: String, contentType: String, totalSize: Long, partSize: Int? = null) =
        client.post("$baseUrl/uploads") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("filename", filename); put("content_type", contentType)
                put("total_size", totalSize)
                if (partSize != null) put("part_size", partSize)
            })
        }.unwrap<UploadSessionResponse>().upload

    /** Progress for a session — which parts already landed, so resume can skip them. */
    suspend fun uploadStatus(uploadId: String) =
        client.get("$baseUrl/uploads/$uploadId") { auth() }.unwrap<UploadSessionResponse>().upload

    suspend fun uploadPart(uploadId: String, partNumber: Int, bytes: ByteArray) {
        client.put("$baseUrl/uploads/$uploadId/parts/$partNumber") { auth(); setBody(bytes) }
            .unwrap<OkResponse>()
    }

    suspend fun completeUpload(uploadId: String) =
        client.post("$baseUrl/uploads/$uploadId/complete") { auth() }
            .unwrap<UploadCompleteResponse>().attachment

    suspend fun abortUpload(uploadId: String) {
        client.delete("$baseUrl/uploads/$uploadId") { auth() }.unwrap<OkResponse>()
    }


    /**
     * Send one of the nest's stickers (2.9.5).
     *
     * Only the id travels: the server resolves the media key from `space_emojis`
     * and stamps it into the message metadata, so a client can't point a
     * "sticker" at an arbitrary media key.
     */
    suspend fun sendSticker(channelId: String, stickerId: String, nonce: String): SendResponse =
        client.post("$baseUrl/channels/$channelId/messages") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("content", ""); put("nonce", nonce); put("kind", "sticker")
                put("metadata", buildJsonObject { put("sticker_id", stickerId) })
            })
        }.unwrap<SendResponse>()


    /** Preview an SPC- invite without joining or consuming a use (2.9.5). */
    suspend fun invitePreview(code: String) =
        client.get("$baseUrl/spaces/invites/${q(code)}/preview") { auth() }.unwrap<InvitePreviewResponse>()


    // --- v3: bots you own ---
    suspend fun bots() = client.get("$baseUrl/bots") { auth() }.unwrap<BotsResponse>().bots

    /** The raw token comes back exactly once — show it, then it is gone. */
    suspend fun createBot(name: String, description: String?) = client.post("$baseUrl/bots") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("name", name)
            if (!description.isNullOrBlank()) put("description", description)
        })
    }.unwrap<BotCreatedResponse>()

    suspend fun updateBot(botId: String, fields: Map<String, String?>) =
        client.patch("$baseUrl/bots/$botId") {
            auth(); contentType(ContentType.Application.Json)
            setBody(JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
        }.unwrap<BotResponse>().bot

    suspend fun rotateBotToken(botId: String) =
        client.post("$baseUrl/bots/$botId/token") { auth() }.unwrap<BotTokenResponse>()

    suspend fun deleteBot(botId: String) {
        client.delete("$baseUrl/bots/$botId") { auth() }.unwrap<OkResponse>()
    }

    suspend fun botCommands(botId: String) =
        client.get("$baseUrl/bots/$botId/commands") { auth() }.unwrap<BotCommandsResponse>().commands

    suspend fun botSpaces(botId: String) =
        client.get("$baseUrl/bots/$botId/spaces") { auth() }.unwrap<BotSpacesResponse>().spaces

    /** Add the bot to a nest you own or manage. */
    suspend fun botJoinSpace(botId: String, spaceId: String) {
        client.post("$baseUrl/bots/$botId/join") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("space_id", spaceId) })
        }.unwrap<JsonObject>()
    }

    suspend fun botLeaveSpace(botId: String, spaceId: String) {
        client.delete("$baseUrl/bots/$botId/spaces/$spaceId") { auth() }.unwrap<OkResponse>()
    }

    // --- v3: bot slash commands ---
    /** Commands usable in this channel: every bot in the nest, or the peer bot in a DM. */
    suspend fun channelCommands(channelId: String) =
        client.get("$baseUrl/channels/$channelId/commands") { auth() }
            .unwrap<ChannelCommandsResponse>().commands

    /** Run one. The server posts the invocation and the bot's reply into the channel. */
    suspend fun sendInteraction(
        channelId: String,
        command: String,
        options: Map<String, String>,
        botId: String? = null,
    ) = client.post("$baseUrl/channels/$channelId/interactions") {
        auth(); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("command", command)
            if (botId != null) put("bot_id", botId)
            put("options", buildJsonObject { options.forEach { (k, v) -> put(k, v) } })
        })
    }.unwrap<InteractionResponseDto>()

    /** Every emoji + sticker from every nest the caller belongs to (2.9.5). */
    suspend fun myEmojis() =
        client.get("$baseUrl/spaces/emojis/mine") { auth() }.unwrap<SpaceEmojisResponse>().emojis


    // --- v2.9.7: moderation ---
    suspend fun kickMember(spaceId: String, userId: String) {
        client.delete("$baseUrl/spaces/$spaceId/members/$userId") { auth() }.unwrap<OkResponse>()
    }

    suspend fun banMember(spaceId: String, userId: String, reason: String? = null) {
        client.post("$baseUrl/spaces/$spaceId/bans") {
            auth(); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("user_id", userId)
                if (reason != null) put("reason", reason)
            })
        }.unwrap<OkResponse>()
    }

}
