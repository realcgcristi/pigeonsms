package app.pigeonsms.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias Snowflake = String

@Serializable
data class ProtocolInfo(val name: String, val versions: List<String>, val preferred: String)

@Serializable
data class ServerInfo(val name: String, val version: String, val source: String? = null)

@Serializable
data class Endpoints(val api: String, val gateway: String, val media: String, val calls: String? = null)

@Serializable
data class ServerLimits(
    @SerialName("message_length") val messageLength: Int,
    @SerialName("upload_bytes") val uploadBytes: Long,
)

@Serializable
data class Discovery(
    val protocol: ProtocolInfo,
    val server: ServerInfo,
    val endpoints: Endpoints,
    val capabilities: List<String>,
    val limits: ServerLimits,
)

@Serializable
data class User(
    val id: Snowflake,
    val username: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_key") val avatarKey: String? = null,
    @SerialName("avatar_original_key") val avatarOriginalKey: String? = null,
    @SerialName("avatar_square_key") val avatarSquareKey: String? = null,
    val accent: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_bot") val isBot: Boolean = false,
)

@Serializable
data class Attachment(val key: String, val name: String? = null, val type: String? = null, val size: Long? = null)

@Serializable
data class Message(
    val id: Snowflake,
    @SerialName("channel_id") val channelId: Snowflake,
    @SerialName("author_id") val authorId: Snowflake? = null,
    val author: User,
    val content: String,
    val seq: Long? = null,
    val kind: String? = null,
    val nonce: String? = null,
    @SerialName("reply_to") val replyTo: Snowflake? = null,
    val attachment: Attachment? = null,
    val metadata: JsonObject? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("edited_at") val editedAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val deleted: Boolean = false,
    val encrypted: Boolean = false,
)

@Serializable
data class Channel(
    val id: Snowflake,
    @SerialName("space_id") val spaceId: Snowflake? = null,
    val name: String? = null,
    val topic: String? = null,
    val kind: String? = null,
    @SerialName("last_seq") val lastSeq: Long? = null,
    @SerialName("last_read_seq") val lastReadSeq: Long? = null,
    val unread: Int = 0,
    @SerialName("category_id") val categoryId: Snowflake? = null,
    val position: Int? = null,
)

@Serializable
data class Space(
    val id: Snowflake,
    val name: String,
    @SerialName("owner_id") val ownerId: Snowflake,
    val description: String? = null,
    @SerialName("icon_key") val iconKey: String? = null,
    val role: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
    val channels: List<Channel> = emptyList(),
)

@Serializable
data class DirectMessage(
    @SerialName("channel_id") val channelId: Snowflake,
    @SerialName("last_seq") val lastSeq: Long,
    val unread: Int,
    val peer: User,
)

@Serializable
data class MessageCursor(
    @SerialName("first_seq") val firstSeq: Long? = null,
    @SerialName("last_seq") val lastSeq: Long? = null,
    @SerialName("channel_last_seq") val channelLastSeq: Long? = null,
    @SerialName("has_more_after") val hasMoreAfter: Boolean = false,
)

@Serializable
data class MessagePage(
    val messages: List<Message>,
    val read: Map<Snowflake, Long>? = null,
    val cursor: MessageCursor? = null,
)

@Serializable
data class SendMessage(
    val content: String,
    val nonce: String = java.util.UUID.randomUUID().toString(),
    @SerialName("reply_to") val replyTo: Snowflake? = null,
    val attachment: Attachment? = null,
    val ttl: Long? = null,
    @SerialName("send_at") val sendAt: Long? = null,
    val encrypted: Boolean = false,
    val kind: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class GatewayEvent(val t: String, val d: JsonObject)

@Serializable
data class BotCommand(
    val id: Snowflake? = null,
    val name: String,
    val description: String,
    @SerialName("space_id") val spaceId: Snowflake? = null,
    @SerialName("dm_enabled") val dmEnabled: Boolean = true,
    val options: List<JsonObject> = emptyList(),
)

@Serializable
data class BotInteraction(
    val id: Snowflake,
    val command: String,
    @SerialName("channel_id") val channelId: Snowflake,
    @SerialName("space_id") val spaceId: Snowflake? = null,
    val user: User,
    val options: JsonObject = JsonObject(emptyMap()),
    @SerialName("callback_token") val callbackToken: String? = null,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody, @SerialName("request_id") val requestId: String? = null)

@Serializable
data class ApiErrorBody(val code: String, val message: String, val details: JsonElement? = null)
