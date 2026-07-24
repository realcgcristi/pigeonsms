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

// ── v2.8.0: scheduled messages + E2EE local key material ───────────────────

/** Local mirror of the caller's server-side scheduled_messages. */
@Dao
interface ScheduledMessageDao {
    /** Live list of pending scheduled sends, soonest first — feeds the dashboard. */
    @Query("SELECT * FROM scheduled_messages ORDER BY sendAt ASC")
    fun stream(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE channelId = :channelId ORDER BY sendAt ASC")
    fun streamForChannel(channelId: String): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages ORDER BY sendAt ASC")
    suspend fun all(): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ScheduledMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ScheduledMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(item: ScheduledMessageEntity)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Replace the whole cache after a GET /scheduled refresh. */
    @Query("DELETE FROM scheduled_messages")
    suspend fun clear()
}

/** E2EE device identity public keys — own devices + cached peer devices. */
@Dao
interface DeviceKeyDao {
    @Query("SELECT * FROM device_keys WHERE userId = :userId ORDER BY createdAt ASC")
    suspend fun forUser(userId: String): List<DeviceKeyEntity>

    @Query("SELECT * FROM device_keys WHERE userId = :userId ORDER BY createdAt ASC")
    fun streamForUser(userId: String): Flow<List<DeviceKeyEntity>>

    /** This install's own registered device(s). */
    @Query("SELECT * FROM device_keys WHERE isSelf = 1 ORDER BY createdAt ASC")
    suspend fun ownDevices(): List<DeviceKeyEntity>

    @Query("SELECT * FROM device_keys WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): DeviceKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(devices: List<DeviceKeyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(device: DeviceKeyEntity)

    @Query("DELETE FROM device_keys WHERE id = :id")
    suspend fun delete(id: String)

    /** Refresh a peer's cached device set after GET /users/:id/devices. */
    @Query("DELETE FROM device_keys WHERE userId = :userId AND isSelf = 0")
    suspend fun clearPeer(userId: String)
}

/** Serialized Double Ratchet state, one row per channel. */
@Dao
interface RatchetStateDao {
    @Query("SELECT * FROM ratchet_state WHERE channelId = :channelId LIMIT 1")
    suspend fun get(channelId: String): RatchetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: RatchetStateEntity)

    @Query("DELETE FROM ratchet_state WHERE channelId = :channelId")
    suspend fun delete(channelId: String)
}

/** Local cache of the password-derived encrypted key backup blob. */
@Dao
interface KeyBackupDao {
    @Query("SELECT * FROM key_backups WHERE userId = :userId LIMIT 1")
    suspend fun get(userId: String): KeyBackupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(backup: KeyBackupEntity)

    @Query("DELETE FROM key_backups WHERE userId = :userId")
    suspend fun delete(userId: String)
}

/** Cached per-DM key envelopes addressed to the caller's own devices. */
@Dao
interface KeyEnvelopeDao {
    @Query("SELECT * FROM key_envelopes WHERE channelId = :channelId ORDER BY createdAt ASC")
    suspend fun forChannel(channelId: String): List<KeyEnvelopeEntity>

    @Query("SELECT * FROM key_envelopes WHERE channelId = :channelId AND toDevice = :toDevice ORDER BY createdAt ASC")
    suspend fun forDevice(channelId: String, toDevice: String): List<KeyEnvelopeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(envelopes: List<KeyEnvelopeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(envelope: KeyEnvelopeEntity)

    @Query("DELETE FROM key_envelopes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM key_envelopes WHERE channelId = :channelId")
    suspend fun clearChannel(channelId: String)
}
