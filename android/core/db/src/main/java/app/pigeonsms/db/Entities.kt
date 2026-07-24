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
