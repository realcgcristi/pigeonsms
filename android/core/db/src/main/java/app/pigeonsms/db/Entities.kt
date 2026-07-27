package app.pigeonsms.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached message. Optimistic sends live here too (state = SENDING). */
@Entity(tableName = "messages", indices = [Index(value = ["channelId", "seq"]), Index("nonce")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val seq: Long,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String?,
    val authorAccent: String?,
    val content: String,
    val replyTo: String?,
    val nonce: String?,
    val attachmentKey: String?,
    val attachmentName: String?,
    val attachmentType: String?,
    val attachmentSize: Long?,
    val createdAt: Long,
    val editedAt: Long?,
    val deleted: Boolean,
    val reactionsJson: String,   // JSON array of {emoji,count,me}
    val revisionsJson: String?,  // admin-only edit history
    val kind: String? = null,        // poll | event | sticker | ... — null means plain text
    val metadataJson: String? = null, // kind-specific blob (event title/starts_at/…)
    val pollJson: String? = null,     // serialized PollDto snapshot (options, votes, me)
    val state: String = "SENT",  // SENDING | SENT | FAILED
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val nonce: String,
    val channelId: String,
    val content: String,
    val replyTo: String?,
    val attachmentKey: String?,
    val attachmentName: String?,
    val attachmentType: String?,
    val attachmentSize: Long?,
    val createdAt: Long,
    val attempts: Int = 0,
)

/** Per-channel sync cursor for the reconnect resume protocol. */
@Entity(tableName = "channel_cursor")
data class ChannelCursorEntity(
    @PrimaryKey val channelId: String,
    val lastSeq: Long,
)

// ── v2.8.0: scheduled messages + E2EE local key material ───────────────────
// All tables below are additive (added in MIGRATION_2_3). E2EE ships flag-off
// and experimental; the server only ever stores ciphertext and never decrypts.

/**
 * Local mirror of the caller's server-side scheduled_messages (send_at in the
 * future). Optional cache so the dashboard can list/cancel pending sends
 * offline; the server remains the source of truth. Mirrors the backend
 * scheduled_messages shape (id, channelId, content, sendAt, createdAt).
 */
@Entity(tableName = "scheduled_messages", indices = [Index("sendAt")])
data class ScheduledMessageEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val authorId: String,
    val content: String,
    val metadataJson: String? = null,
    val nonce: String? = null,
    @ColumnInfo(defaultValue = "0") val encrypted: Boolean = false,
    val sendAt: Long,
    val createdAt: Long,
)

/**
 * A known E2EE device (identity) public key. Mirrors backend user_devices.
 * Rows for the local user's own devices AND cached peer devices (fetched via
 * GET /users/:id/devices) so we can wrap per-DM keys to every recipient device.
 */
@Entity(tableName = "device_keys", indices = [Index("userId")])
data class DeviceKeyEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val pubKey: String,          // base64 X25519 identity public key
    val name: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    val lastSeen: Long? = null,
    @ColumnInfo(defaultValue = "0") val isSelf: Boolean = false, // true for this install's own device(s)
)

/**
 * Serialized Double Ratchet state for a channel's E2EE message stream.
 * One row per channelId; [stateBlob] is the opaque serialized ratchet state
 * (base64/JSON produced by the crypto layer). Never leaves the device.
 */
@Entity(tableName = "ratchet_state")
data class RatchetStateEntity(
    @PrimaryKey val channelId: String,
    val stateBlob: String,       // serialized ratchet state (opaque to Room)
    val updatedAt: Long,
)

/**
 * Local cache of the password-derived encrypted key backup blob (multi-device
 * recovery). Mirrors backend key_backups; one row per userId. Argon2id/scrypt
 * params + salt stored alongside the ciphertext blob.
 */
@Entity(tableName = "key_backups")
data class KeyBackupEntity(
    @PrimaryKey val userId: String,
    val blob: String,            // base64 ciphertext of the wrapped key bundle
    val kdfSalt: String,
    val kdfParams: String,       // JSON KDF params (algo, memory, iterations…)
    val updatedAt: Long,
)

/**
 * Cached per-DM symmetric key envelope: a sealed-box-wrapped channel key
 * addressed to one of the caller's own devices. Mirrors backend key_envelopes.
 * Consumed to unwrap the channel key, then may be dropped.
 */
@Entity(tableName = "key_envelopes", indices = [Index(value = ["channelId", "toDevice"])])
data class KeyEnvelopeEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val toDevice: String,        // recipient device id (one of ours)
    val fromUser: String,
    val wrappedKey: String,      // base64 sealed-box ciphertext of the channel key
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

// ── v2.9.0: offline cache for the shell (nests, channels, DMs, friends) ─────
//
// Until now Room held *only* messages, so a cold start without network showed an
// empty app even though every conversation was cached: the DM list, nest list and
// friends list were fetched live on every open. These four tables close that gap.
//
// They are a **cache, not a source of truth** — each refresh replaces the whole
// set for that list (see the `replaceAll` DAO methods), which is why every row
// carries a `position` mirroring the server's ordering rather than the client
// re-sorting. Anything the server stops returning simply disappears on the next
// refresh, so a left nest or removed friend can't linger.

/** One row per nest the user belongs to. Mirrors the network `SpaceDto`. */
@Entity(tableName = "spaces_cache")
data class SpaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerId: String,
    val iconKey: String?,
    val iconOriginalKey: String?,
    val iconSquareKey: String?,
    val description: String?,
    val role: String,
    val memberCount: Int,
    /** Server ordering; the list is rendered in ascending position. */
    val position: Int,
)

/** Channels belonging to a cached nest. Mirrors the network `ChannelDto`. */
@Entity(tableName = "channels_cache", indices = [Index("spaceId")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val spaceId: String,
    val name: String?,
    val topic: String?,
    val lastSeq: Long,
    val unread: Int,
    val kind: String,
    val position: Int,
)

/**
 * The conversation list. Keyed by channel id (what every downstream screen
 * navigates by) with the peer flattened inline — a DM has exactly one peer, so a
 * join table would buy nothing.
 */
@Entity(tableName = "dms_cache")
data class DmEntity(
    @PrimaryKey val channelId: String,
    val peerId: String,
    val peerUsername: String,
    val peerDisplayName: String?,
    val peerAvatarKey: String?,
    val peerAccent: String?,
    val peerStatusText: String?,
    val peerLastOnline: Long?,
    val lastSeq: Long,
    val unread: Int,
    val lastMessageContent: String?,
    val lastMessageCreatedAt: Long?,
    val lastMessageDeleted: Boolean,
    val position: Int,
)

/**
 * Friends and pending requests. `bucket` is which list the row came from
 * (`friends` / `incoming` / `outgoing`) and is part of the key, because the same
 * user can legitimately appear in more than one list mid-flow.
 */
@Entity(tableName = "friends_cache", primaryKeys = ["id", "bucket"])
data class FriendEntity(
    val id: String,
    val bucket: String,
    val username: String,
    val displayName: String?,
    val avatarKey: String?,
    val accent: String?,
    val statusText: String?,
    val lastOnline: Long?,
    val note: String?,
    val closeFriend: Int,
    val position: Int,
)
