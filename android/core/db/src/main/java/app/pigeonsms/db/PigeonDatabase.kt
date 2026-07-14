package app.pigeonsms.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN metadataJson TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN pollJson TEXT")
    }
}

@Database(
    entities = [MessageEntity::class, OutboxEntity::class, ChannelCursorEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PigeonDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun outbox(): OutboxDao
    abstract fun cursors(): CursorDao

    companion object {
        @Volatile private var instance: PigeonDatabase? = null
        fun get(context: Context): PigeonDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PigeonDatabase::class.java,
                "pigeon.db",
            ).addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
