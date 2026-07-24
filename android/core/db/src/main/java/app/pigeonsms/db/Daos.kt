package app.pigeonsms.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE channelId = :channelId ORDER BY seq ASC, createdAt ASC")
    fun stream(channelId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE messages SET deleted = 1, state = 'SENT', reactionsJson = '[]' WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("SELECT MIN(seq) FROM messages WHERE channelId = :channelId AND seq > 0")
    suspend fun oldestSeq(channelId: String): Long?

    @Query("DELETE FROM messages WHERE channelId = :channelId AND seq < :seq AND state = 'SENT'")
    suspend fun deleteBelow(channelId: String, seq: Long)

    @Query("SELECT * FROM messages WHERE nonce = :nonce LIMIT 1")
    suspend fun byNonce(nonce: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): MessageEntity?

    @Query("UPDATE messages SET state = :state WHERE nonce = :nonce")
    suspend fun setState(nonce: String, state: String)

    /**
     * All image/video attachments in a conversation, newest first — feeds the media grid.
     * Orders by createdAt (not seq): a SENDING attachment carries a synthetic pending seq
     * (Long.MAX_VALUE-based) that would otherwise pin it to the top until the echo lands.
     */
    @Query(
        "SELECT * FROM messages WHERE channelId = :channelId AND deleted = 0 " +
            "AND attachmentKey IS NOT NULL " +
            "AND (attachmentType LIKE 'image/%' OR attachmentType LIKE 'video/%') " +
            "ORDER BY createdAt DESC, seq DESC"
    )
    fun mediaStream(channelId: String): Flow<List<MessageEntity>>

    /** Local (offline) message text search. Callers must escape %, _ and \ in [query]. */
    @Query(
        "SELECT * FROM messages WHERE channelId = :channelId AND deleted = 0 " +
            "AND content LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY seq DESC, createdAt DESC LIMIT 200"
    )
    suspend fun searchLocal(channelId: String, query: String): List<MessageEntity>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE channelId = :channelId ORDER BY createdAt ASC")
    suspend fun forChannel(channelId: String): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE nonce = :nonce")
    suspend fun remove(nonce: String)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE nonce = :nonce")
    suspend fun bumpAttempt(nonce: String)

    /** Explicit user retry clears the attempt budget so a dead-lettered item can send again. */
    @Query("UPDATE outbox SET attempts = 0 WHERE nonce = :nonce")
    suspend fun resetAttempts(nonce: String)
}

@Dao
interface CursorDao {
    @Query("SELECT lastSeq FROM channel_cursor WHERE channelId = :channelId")
    suspend fun get(channelId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(cursor: ChannelCursorEntity)
}
