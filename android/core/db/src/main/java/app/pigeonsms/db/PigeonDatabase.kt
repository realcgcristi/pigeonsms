package app.pigeonsms.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2: message kind + poll/event payload columns (nullable, no SQL defaults). */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN metadataJson TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN pollJson TEXT")
    }
}

/**
 * v3 (app 2.8.0): additive local tables for scheduled-message cache + E2EE key
 * material (device keys, per-channel ratchet state, key backup, key envelopes).
 * Column types/defaults must match the Room entity definitions exactly, or Room's
 * schema validation will fail on open. Purely additive — no existing table touched.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scheduled_messages` (" +
                "`id` TEXT NOT NULL, `channelId` TEXT NOT NULL, `authorId` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `metadataJson` TEXT, `nonce` TEXT, " +
                "`encrypted` INTEGER NOT NULL DEFAULT 0, `sendAt` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_messages_sendAt` ON `scheduled_messages` (`sendAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `device_keys` (" +
                "`id` TEXT NOT NULL, `userId` TEXT NOT NULL, `pubKey` TEXT NOT NULL, " +
                "`name` TEXT, `createdAt` INTEGER NOT NULL DEFAULT 0, `lastSeen` INTEGER, " +
                "`isSelf` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_keys_userId` ON `device_keys` (`userId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ratchet_state` (" +
                "`channelId` TEXT NOT NULL, `stateBlob` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `key_backups` (" +
                "`userId` TEXT NOT NULL, `blob` TEXT NOT NULL, `kdfSalt` TEXT NOT NULL, " +
                "`kdfParams` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `key_envelopes` (" +
                "`id` TEXT NOT NULL, `channelId` TEXT NOT NULL, `toDevice` TEXT NOT NULL, " +
                "`fromUser` TEXT NOT NULL, `wrappedKey` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_key_envelopes_channelId_toDevice` " +
                "ON `key_envelopes` (`channelId`, `toDevice`)"
        )
    }
}

/**
 * v4 (app 2.9.0): the offline cache stops being messages-only. Adds the shell
 * lists — nests, their channels, the DM list and the friends lists — so a cold
 * start with no network renders the app the user actually has instead of an empty
 * shell. Purely additive; no existing table is touched, so an interrupted upgrade
 * can only lose cache, never the outbox.
 *
 * Column types must match the Room entity definitions exactly (Room validates the
 * schema on open and throws otherwise): `String?` → nullable TEXT, `Long`/`Int`/
 * `Boolean` → INTEGER NOT NULL.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spaces_cache` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `ownerId` TEXT NOT NULL, " +
                "`iconKey` TEXT, `iconOriginalKey` TEXT, `iconSquareKey` TEXT, " +
                "`description` TEXT, `role` TEXT NOT NULL, `memberCount` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `channels_cache` (" +
                "`id` TEXT NOT NULL, `spaceId` TEXT NOT NULL, `name` TEXT, `topic` TEXT, " +
                "`lastSeq` INTEGER NOT NULL, `unread` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_cache_spaceId` ON `channels_cache` (`spaceId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dms_cache` (" +
                "`channelId` TEXT NOT NULL, `peerId` TEXT NOT NULL, `peerUsername` TEXT NOT NULL, " +
                "`peerDisplayName` TEXT, `peerAvatarKey` TEXT, `peerAccent` TEXT, " +
                "`peerStatusText` TEXT, `peerLastOnline` INTEGER, `lastSeq` INTEGER NOT NULL, " +
                "`unread` INTEGER NOT NULL, `lastMessageContent` TEXT, " +
                "`lastMessageCreatedAt` INTEGER, `lastMessageDeleted` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`channelId`))"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `friends_cache` (" +
                "`id` TEXT NOT NULL, `bucket` TEXT NOT NULL, `username` TEXT NOT NULL, " +
                "`displayName` TEXT, `avatarKey` TEXT, `accent` TEXT, `statusText` TEXT, " +
                "`lastOnline` INTEGER, `note` TEXT, `closeFriend` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`id`, `bucket`))"
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER")
        db.execSQL("ALTER TABLE outbox ADD COLUMN ttl INTEGER")
        db.execSQL("ALTER TABLE outbox ADD COLUMN sendAt INTEGER")
        db.execSQL("ALTER TABLE outbox ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE key_envelopes ADD COLUMN keyId TEXT")
    }
}

@Database(
    entities = [
        MessageEntity::class,
        OutboxEntity::class,
        ChannelCursorEntity::class,
        ScheduledMessageEntity::class,
        DeviceKeyEntity::class,
        RatchetStateEntity::class,
        KeyBackupEntity::class,
        KeyEnvelopeEntity::class,
        SpaceEntity::class,
        ChannelEntity::class,
        DmEntity::class,
        FriendEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class PigeonDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun outbox(): OutboxDao
    abstract fun cursors(): CursorDao
    abstract fun scheduled(): ScheduledMessageDao
    abstract fun deviceKeys(): DeviceKeyDao
    abstract fun ratchets(): RatchetStateDao
    abstract fun keyBackups(): KeyBackupDao
    abstract fun keyEnvelopes(): KeyEnvelopeDao
    abstract fun shellCache(): ShellCacheDao

    companion object {
        @Volatile private var instance: PigeonDatabase? = null
        fun get(context: Context): PigeonDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PigeonDatabase::class.java,
                "pigeon.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // last-resort only — a destructive fallback drops the unsent outbox
                .build().also { instance = it }
        }
    }
}
