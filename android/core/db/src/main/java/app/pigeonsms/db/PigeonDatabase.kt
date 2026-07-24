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
    ],
    version = 3,
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

    companion object {
        @Volatile private var instance: PigeonDatabase? = null
        fun get(context: Context): PigeonDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PigeonDatabase::class.java,
                "pigeon.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // last-resort only — a destructive fallback drops the unsent outbox
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
