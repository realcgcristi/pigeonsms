package app.pigeonsms.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiUser(
    val id: String,
    val username: String,
    val email: String = "",
    val display_name: String? = null,
    val avatar_key: String? = null,
    val accent: String? = null,
    val is_admin: Boolean = false,
)

@Serializable
data class AuthResponse(val token: String, val user: ApiUser)

@Serializable
data class MeResponse(val user: ApiUser)

@Serializable
data class SessionDto(
    val id: String,
    val device_name: String? = null,
    val user_agent: String? = null,
    val ip: String? = null,
    val created_at: Long = 0,
    val last_seen: Long = 0,
    val current: Boolean = false,
)

@Serializable data class SessionsResponse(val sessions: List<SessionDto>)

@Serializable
data class HistoryEntry(
    val ip: String? = null,
    val user_agent: String? = null,
    val device_name: String? = null,
    val success: Int = 0,
    val created_at: Long = 0,
)

@Serializable data class HistoryResponse(val history: List<HistoryEntry>)

@Serializable
data class BlockedUserDto(
    val id: String,
    val username: String,
    val display_name: String? = null,
    val avatar_key: String? = null,
)

@Serializable data class BlocksResponse(val blocks: List<BlockedUserDto>)
@Serializable data class InviteCheckResponse(val valid: Boolean)
@Serializable data class OkResponse(val ok: Boolean = true)

@Serializable
data class NotificationPreferenceDto(
    val scope_type: String = "global",
    val scope_id: String = "",
    val mode: String = "all",
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val badge: Boolean = true,
    val quiet_start: String? = null,
    val quiet_end: String? = null,
    val updated_at: Long? = null,
)

@Serializable
data class NotificationPreferencesResponse(
    val defaults: NotificationPreferenceDto = NotificationPreferenceDto(),
    val preferences: List<NotificationPreferenceDto> = emptyList(),
)

@Serializable
data class ReactionDto(val emoji: String, val count: Int = 0, val me: Boolean = false)

@Serializable
data class ReactionMutationResponse(
    val ok: Boolean,
    val changed: Boolean,
    val reaction: ReactionDto,
)

@Serializable
data class RevisionDto(val content: String, val edited_at: Long)

@Serializable
data class AttachmentDto(val key: String, val name: String? = null, val type: String? = null, val size: Long? = null)

@Serializable
data class PollOptionDto(
    val id: String,
    val position: Int = 0,
    val text: String,
    val votes: Int = 0,
    val me: Boolean = false,
)

@Serializable
data class PollDto(
    val question: String = "",
    val anonymous: Boolean = false,
    val multiple_choice: Boolean = false,
    val total_votes: Int = 0,
    val options: List<PollOptionDto> = emptyList(),
)

/** PUT /messages/:id/poll/votes/:optionId and DELETE /messages/:id/poll/vote. */
@Serializable
data class PollVoteResponse(val ok: Boolean = true, val changed: Boolean = false, val poll: PollDto? = null)

/** Metadata blob for kind == "event" messages. Timestamps are epoch millis. */
@Serializable
data class EventMetadataDto(
    val title: String,
    val starts_at: Long,
    val ends_at: Long? = null,
    val location: String? = null,
    val description: String? = null,
)

/** Gateway `poll.update` payload — authoritative per-option counts (no `me` info). */
@Serializable data class PollOptionCountDto(val id: String, val votes: Int)

@Serializable
data class PollUpdateEventDto(
    val message_id: String,
    val channel_id: String,
    val options: List<PollOptionCountDto> = emptyList(),
)

@Serializable
data class MessageDto(
    val id: String,
    val channel_id: String,
    val seq: Long = 0,
    val author: ApiUser,
    val content: String = "",
    val encrypted: Boolean = false,
    val reply_to: String? = null,
    val nonce: String? = null,
    val attachment: AttachmentDto? = null,
    val created_at: Long = 0,
    val edited_at: Long? = null,
    val deleted: Boolean = false,
    val reactions: List<ReactionDto> = emptyList(),
    val revisions: List<RevisionDto>? = null,
    val kind: String? = null,
    val metadata: JsonElement? = null,
    val poll: PollDto? = null,
    val thread_id: String? = null,
)

/** GET /channels/:id/messages cursor — channel_last_seq covers replies past the page. */
@Serializable
data class MessagesCursorDto(
    val first_seq: Long? = null,
    val last_seq: Long? = null,
    val channel_last_seq: Long = 0,
    val has_more_after: Boolean = false,
)

@Serializable data class MessagesResponse(val messages: List<MessageDto>, val read: Map<String, Long>? = null, val cursor: MessagesCursorDto? = null)
@Serializable data class MessageResponse(val message: MessageDto)

/**
 * POST /channels/:id/messages response. A normal send returns { message: {...} } (201);
 * a future send_at returns { scheduled: {...} } (202, NO message key). Both fields are
 * nullable so kotlinx doesn't throw MissingFieldException on the scheduled branch.
 */
@Serializable data class SendResponse(val message: MessageDto? = null, val scheduled: ScheduledMessageDto? = null)
@Serializable data class SuperPinDto(val message: MessageDto, val pinned_by: String, val created_at: Long = 0, val dismissed: Boolean = false)
@Serializable data class SuperPinResponse(val super_pin: SuperPinDto? = null)

/** Gateway `pin.add` / `pin.remove` payloads (pinned_by only rides pin.add). */
@Serializable data class PinEventDto(val channel_id: String, val message_id: String, val pinned_by: String? = null)

/** Gateway `super_pin.set` payload — the full banner message plus what it replaced. */
@Serializable data class SuperPinSetEventDto(val channel_id: String, val message: MessageDto, val replaced_message_id: String? = null)

/** Gateway `super_pin.remove` payload — message_id may be null when unknown. */
@Serializable data class SuperPinRemoveEventDto(val channel_id: String, val message_id: String? = null)

@Serializable
data class LastMessageDto(val content: String, val created_at: Long, val deleted: Boolean = false)

@Serializable
data class PeerDto(
    val id: String,
    val username: String,
    val display_name: String? = null,
    val avatar_key: String? = null,
    val accent: String? = null,
    val status_text: String? = null,
    val last_online: Long? = null,
)

@Serializable
data class DmDto(
    val channel_id: String,
    val last_seq: Long,
    val unread: Int,
    val peer: PeerDto,
    val last_message: LastMessageDto? = null,
)

@Serializable data class DmsResponse(val dms: List<DmDto>)
@Serializable data class OpenDmResponse(val channel_id: String)

@Serializable
data class FriendDto(
    val id: String,
    val username: String,
    val display_name: String? = null,
    val avatar_key: String? = null,
    val accent: String? = null,
    val status_text: String? = null,
    val last_online: Long? = null,
    val note: String? = null,
    val close_friend: Int = 0,
)

@Serializable
data class FriendsResponse(
    val friends: List<FriendDto> = emptyList(),
    val incoming: List<FriendDto> = emptyList(),
    val outgoing: List<FriendDto> = emptyList(),
)

@Serializable data class UsersSearchResponse(val users: List<ApiUser> = emptyList())

@Serializable
data class ChannelDto(
    val id: String,
    val name: String? = null,
    val topic: String? = null,
    val last_seq: Long = 0,
    val unread: Int = 0,
    val kind: String = "text",
)

@Serializable
data class SpaceDto(
    val id: String,
    val name: String,
    val owner_id: String,
    val icon_key: String? = null,
    val icon_original_key: String? = null,
    val icon_square_key: String? = null,
    val description: String? = null,
    val role: String = "member",
    val member_count: Int = 0,
    val channels: List<ChannelDto> = emptyList(),
)

/** Gateway `channel.update` payload — a channel renamed/edited by the owner. */
@Serializable
data class ChannelUpdateEventDto(
    val id: String,
    val space_id: String,
    val name: String? = null,
    val topic: String? = null,
    val kind: String = "text",
)

/** Gateway `channel.delete` payload. */
@Serializable data class ChannelDeleteEventDto(val id: String, val space_id: String)

@Serializable data class SpacesResponse(val spaces: List<SpaceDto> = emptyList())
@Serializable data class CreateSpaceResponse(val space: SpaceDto)
@Serializable data class SpaceResponse(val space: SpaceDto)
@Serializable data class CreateChannelResponse(val channel: ChannelDto)
@Serializable data class SpaceInviteResponse(val code: String, val expires_at: Long? = null)
@Serializable data class JoinSpaceResponse(val space_id: String)

@Serializable
data class SpaceMemberDto(
    val id: String,
    val username: String,
    val display_name: String? = null,
    val avatar_key: String? = null,
    val accent: String? = null,
    val last_online: Long? = null,
    val role: String,
)
@Serializable data class MembersResponse(val members: List<SpaceMemberDto> = emptyList())

@Serializable
data class ProfileDto(
    val id: String,
    val username: String,
    val display_name: String? = null,
    val about: String? = null,
    val accent: String? = null,
    val avatar_key: String? = null,
    val banner_key: String? = null,
    val banner_color: String? = null,
    val pronouns: String? = null,
    val status_text: String? = null,
    val badges: List<String> = emptyList(),
    val last_online: Long? = null,
    val created_at: Long = 0,
)
@Serializable
data class MutualSpaceDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon_key: String? = null,
    val icon_square_key: String? = null,
    val member_count: Int = 0,
)
@Serializable data class ProfileResponse(val profile: ProfileDto, val mutual_spaces: List<MutualSpaceDto> = emptyList())

/** Forum tag definition — POST/GET /channels/:id/forum/tags. */
@Serializable
data class ForumTagDto(val id: String, val name: String, val mark_label: String? = null)

@Serializable data class ForumTagResponse(val tag: ForumTagDto)
@Serializable data class ForumTagsResponse(val tags: List<ForumTagDto> = emptyList())

/**
 * Forum post summary from GET /channels/:id/forum/posts — a serialized message
 * (kind == "forum_post", metadata.title holds the post title) plus aggregate
 * thread stats computed server-side.
 */
@Serializable
data class ForumPostDto(
    val id: String,
    val channel_id: String,
    val seq: Long,
    val author: ApiUser,
    val content: String,
    val attachment: AttachmentDto? = null,
    val created_at: Long,
    val edited_at: Long? = null,
    val deleted: Boolean = false,
    val kind: String? = null,
    val metadata: JsonElement? = null,
    val reactions: List<ReactionDto> = emptyList(),
    val reply_count: Int = 0,
    val last_activity_at: Long = 0,
    val pinned: Boolean = false,
    val like_count: Int = 0,
    val liked: Boolean = false,
    val marked: Boolean = false,
    val tag: ForumTagDto? = null,
)

@Serializable data class ForumPostsResponse(val posts: List<ForumPostDto> = emptyList())
@Serializable data class ForumPostResponse(val message: ForumPostDto)
@Serializable data class ForumCursorDto(val last_seq: Long? = null)

/** PUT/DELETE /messages/:id/like → the post's new like tally + this user's state. */
@Serializable data class LikeMutationResponse(val like_count: Int = 0, val liked: Boolean = false)

/** PUT/DELETE /messages/:id/marked → whether the post is now marked (400 not_markable). */
@Serializable data class MarkMutationResponse(val marked: Boolean = false)

/** Gateway `forum.like` payload — authoritative like tally (no per-user `liked`). */
@Serializable data class ForumLikeEventDto(val channel_id: String, val message_id: String, val like_count: Int = 0)

/** GET /channels/:id/forum/posts/:postId — root post plus its replies. */
@Serializable
data class ForumThreadResponse(
    val post: MessageDto,
    val replies: List<MessageDto> = emptyList(),
    val cursor: ForumCursorDto? = null,
)

@Serializable data class UploadResponse(val attachment: AttachmentDto)
@Serializable data class AvatarResponse(val avatar_key: String)
@Serializable data class BannerResponse(val key: String)

@Serializable data class TotpSetupResponse(val secret: String, val otpauth: String)
@Serializable data class RecoveryResponse(val recovery_codes: List<String>)

@Serializable
data class ReleaseDto(val version_code: Int = 0, val version_name: String = "", val url: String = "", val notes: String? = null)
@Serializable data class LatestReleaseResponse(val release: ReleaseDto? = null)

/** Gateway event envelope: { "t": "message.new", "d": {...} } */
@Serializable data class GatewayEvent(val t: String, val d: JsonElement)

@Serializable internal data class ErrorEnvelope(val error: ErrorDetail)
@Serializable internal data class ErrorDetail(val code: String, val message: String)

@Serializable data class InviteCodeDto(val code: String, val max_uses: Int = 1)
@Serializable data class GenerateInvitesResponse(val invites: List<InviteCodeDto> = emptyList())

// --- 2.8.0: E2EE, multi-device, key backup, search, scheduled messages ---

/** A user's E2EE device (X25519 identity pub key). GET /auth/devices, GET /users/:id/devices. */
@Serializable
data class DeviceDto(
    val id: String,
    val pub_key: String,
    val name: String? = null,
    val created_at: Long = 0,
    val last_seen: Long? = 0,
)

@Serializable data class DevicesResponse(val devices: List<DeviceDto> = emptyList())

/** Password-derived encrypted key backup blob. GET/PUT /auth/key-backup. */
@Serializable
data class KeyBackupDto(
    val blob: String,
    val kdf_salt: String,
    val kdf_params: String,
    val updated_at: Long = 0,
)

@Serializable data class KeyBackupResponse(val backup: KeyBackupDto? = null)

/** A per-DM symmetric key wrapped (sealed box) to a recipient device. /channels/:id/key-envelopes. */
@Serializable
data class KeyEnvelopeDto(
    val id: String,
    val to_device: String,
    val from_user: String,
    val wrapped_key: String,
    val created_at: Long = 0,
)

@Serializable data class EnvelopesResponse(val envelopes: List<KeyEnvelopeDto> = emptyList())

/** GET /spaces/:id/search — FTS5 message results plus a cursor. */
@Serializable
data class SearchResponse(val results: List<MessageDto> = emptyList(), val next_before: Long? = null)

/** A pending scheduled message. GET /scheduled. */
@Serializable
data class ScheduledMessageDto(
    val id: String,
    val channel_id: String,
    val content: String,
    val send_at: Long = 0,
    val created_at: Long = 0,
)

@Serializable data class ScheduledResponse(val scheduled: List<ScheduledMessageDto> = emptyList())
