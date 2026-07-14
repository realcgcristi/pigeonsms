package app.pigeonsms.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val reactionsJson: String,
    val revisionsJson: String?,  // admin-only edit history
    val kind: String? = null,        // poll | event | sticker | ... — null means plain text
    val metadataJson: String? = null, // kind-specific blob (event title/starts_at/…)
    val pollJson: String? = null,
    val state: String = "SENT",
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

@Entity(tableName = "channel_cursor")
data class ChannelCursorEntity(
    @PrimaryKey val channelId: String,
    val lastSeq: Long,
)
