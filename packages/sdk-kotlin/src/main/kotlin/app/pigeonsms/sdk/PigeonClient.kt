package app.pigeonsms.sdk

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class PigeonApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val requestId: String? = null,
) : RuntimeException(message)

class PigeonClient(
    baseUrl: String,
    private var tokenProvider: suspend () -> String? = { null },
    val http: HttpClient = defaultHttpClient(),
) : AutoCloseable {
    val baseUrl = baseUrl.trimEnd('/')

    fun setToken(token: String?) {
        tokenProvider = { token }
    }

    suspend fun discover(): Discovery = get("/.well-known/pigeon", false)
    suspend fun health(): HealthResponse = get("/health", false)

    suspend fun login(login: String, password: String, totp: String? = null, deviceName: String = "kotlin-sdk"): AuthResponse {
        val response = post<AuthResponse>("/auth/login", LoginRequest(login, password, deviceName, totp), false)
        setToken(response.token)
        return response
    }

    suspend fun me(): User = get<MeResponse>("/auth/me").user
    suspend fun logout() = post<Unit>("/auth/logout", JsonObject(emptyMap()))
    suspend fun dms(): List<DirectMessage> = get<DmsResponse>("/dms").dms
    suspend fun openDm(userId: Snowflake): Snowflake = post<OpenDmResponse>("/dms/open", OpenDmRequest(userId)).channelId

    suspend fun messages(channelId: Snowflake, before: Long? = null, afterSeq: Long? = null, limit: Int? = null): MessagePage {
        val query = listOfNotNull(before?.let { "before=$it" }, afterSeq?.let { "after=$it" }, limit?.let { "limit=$it" }).joinToString("&")
        return get("/channels/${encode(channelId)}/messages${if (query.isEmpty()) "" else "?$query"}")
    }

    suspend fun sendMessage(channelId: Snowflake, message: SendMessage): Message? =
        post<SendResponse>("/channels/${encode(channelId)}/messages", message, idempotencyKey = message.nonce).message

    suspend fun markRead(channelId: Snowflake, seq: Long) = put<Unit>("/channels/${encode(channelId)}/read", ReadRequest(seq))
    suspend fun typing(channelId: Snowflake) = post<Unit>("/channels/${encode(channelId)}/typing", JsonObject(emptyMap()))
    suspend fun spaces(): List<Space> = get<SpacesResponse>("/spaces").spaces
    suspend fun space(id: Snowflake): Space = get<SpaceResponse>("/spaces/${encode(id)}").space
    suspend fun createSpace(name: String, description: String? = null): Space =
        post<CreateSpaceResponse>("/spaces", CreateSpaceRequest(name, description)).space
    suspend fun createChannel(spaceId: Snowflake, name: String, kind: String = "text", categoryId: Snowflake? = null): Channel =
        post<ChannelResponse>("/spaces/${encode(spaceId)}/channels", CreateChannelRequest(name, kind, categoryId)).channel
    suspend fun commands(): List<BotCommand> = get<CommandsResponse>("/bots/me/commands").commands
    suspend fun replaceCommands(commands: List<BotCommand>): List<BotCommand> =
        put<CommandsResponse>("/bots/me/commands", CommandsResponse(commands)).commands
    suspend fun pollInteractions(timeout: Int = 25): List<BotInteraction> =
        get<InteractionsResponse>("/bots/me/updates?timeout=$timeout").interactions
    suspend fun answerInteraction(id: Snowflake, callbackToken: String, response: JsonObject) =
        post<Unit>("/interactions/${encode(id)}/callback", JsonObject(response + ("callback_token" to kotlinx.serialization.json.JsonPrimitive(callbackToken))))

    private suspend inline fun <reified T> get(path: String, auth: Boolean = true): T = decode(http.get("$baseUrl$path") {
        prepare(auth)
    })

    private suspend inline fun <reified T> post(path: String, body: Any, auth: Boolean = true, idempotencyKey: String? = null): T =
        decode(http.post("$baseUrl$path") {
            prepare(auth)
            if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
            setBody(body)
        })

    private suspend inline fun <reified T> put(path: String, body: Any): T = decode(http.put("$baseUrl$path") {
        prepare(true)
        setBody(body)
    })

    suspend fun delete(path: String) {
        decode<Unit>(http.delete("$baseUrl$path") { prepare(true) })
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.prepare(auth: Boolean) {
        accept(ContentType.Application.Json)
        header("Pigeon-Protocol-Version", "1.0")
        header("X-Pigeon-Client", "pigeonsms-kotlin/1.0")
        if (auth) tokenProvider()?.takeUnless { it == "cookie" }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend inline fun <reified T> decode(response: io.ktor.client.statement.HttpResponse): T {
        if (!response.status.isSuccess()) {
            val failure = runCatching { response.body<ApiErrorEnvelope>() }.getOrNull()
            throw PigeonApiException(response.status.value, failure?.error?.code ?: "http_error", failure?.error?.message ?: response.status.description, failure?.requestId)
        }
        if (T::class == Unit::class) return Unit as T
        return response.body()
    }

    override fun close() = http.close()

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun defaultHttpClient() = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
        }

        private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    }
}

@Serializable data class HealthResponse(val ok: Boolean, val ts: Long)
@Serializable data class AuthResponse(val token: String, val user: User)
@Serializable private data class LoginRequest(val login: String, val password: String, @SerialName("device_name") val deviceName: String, val totp: String?)
@Serializable private data class MeResponse(val user: User)
@Serializable private data class DmsResponse(val dms: List<DirectMessage>)
@Serializable private data class OpenDmRequest(@SerialName("user_id") val userId: Snowflake)
@Serializable private data class OpenDmResponse(@SerialName("channel_id") val channelId: Snowflake)
@Serializable private data class SendResponse(val message: Message? = null)
@Serializable private data class ReadRequest(val seq: Long)
@Serializable private data class SpacesResponse(val spaces: List<Space>)
@Serializable private data class SpaceResponse(val space: Space)
@Serializable private data class CreateSpaceRequest(val name: String, val description: String? = null, val nonce: String = java.util.UUID.randomUUID().toString())
@Serializable private data class CreateSpaceResponse(val space: Space)
@Serializable private data class CreateChannelRequest(val name: String, val kind: String, @SerialName("category_id") val categoryId: Snowflake?)
@Serializable private data class ChannelResponse(val channel: Channel)
@Serializable private data class CommandsResponse(val commands: List<BotCommand>)
@Serializable private data class InteractionsResponse(val interactions: List<BotInteraction>)
