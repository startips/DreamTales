package com.bedtime.dreamtales.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bedtime.dreamtales.data.local.dao.PlaybackPositionDao
import com.bedtime.dreamtales.data.local.dao.QueueDao
import com.bedtime.dreamtales.data.local.dao.SleepSessionDao
import com.bedtime.dreamtales.data.local.dao.StoryAudioCacheDao
import com.bedtime.dreamtales.data.local.dao.StoryDao
import com.bedtime.dreamtales.data.local.dao.StoryPageDao
import com.bedtime.dreamtales.data.local.dao.VoiceProfileDao
import com.bedtime.dreamtales.data.local.entity.PlaybackPosition
import com.bedtime.dreamtales.data.local.entity.QueueItem
import com.bedtime.dreamtales.data.local.entity.SleepSession
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.StoryAudioCache
import com.bedtime.dreamtales.data.local.entity.StoryPage
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        VoiceProfile::class,
        Story::class,
        StoryAudioCache::class,
        StoryPage::class,
        SleepSession::class,
        PlaybackPosition::class,
        QueueItem::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun voiceProfileDao(): VoiceProfileDao
    abstract fun storyDao(): StoryDao
    abstract fun storyAudioCacheDao(): StoryAudioCacheDao
    abstract fun storyPageDao(): StoryPageDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stories ADD COLUMN isAiGenerated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stories ADD COLUMN dateLabel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stories ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stories ADD COLUMN iconEmoji TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stories ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stories ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stories ADD COLUMN coverImagePath TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `story_pages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`storyId` INTEGER NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`imagePath` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_story_pages_storyId_pageIndex` " +
                        "ON `story_pages` (`storyId`, `pageIndex`)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`storyId` INTEGER NOT NULL, " +
                        "`storyTitle` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER NOT NULL, " +
                        "`listenedSeconds` INTEGER NOT NULL, " +
                        "`estimatedSleepSeconds` INTEGER NOT NULL, " +
                        "`endedBy` TEXT NOT NULL, " +
                        "`selectedTimerMinutes` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_sessions_startedAt` " +
                        "ON `sleep_sessions` (`startedAt`)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_positions` (" +
                        "`storyId` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`storyId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_queue` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`storyId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_queue_storyId` " +
                        "ON `playback_queue` (`storyId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dreamtales.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // 首次建库预置睡前故事及默认音色
                        CoroutineScope(Dispatchers.IO).launch {
                            val database = getInstance(context)
                            prepopulateData(database)
                        }
                    }
                })
                .build()
        }

        private suspend fun prepopulateData(db: AppDatabase) {
            val now = System.currentTimeMillis()
            db.storyDao().insertAll(ClassicStoriesSeed.getClassicStories())

            // 预置默认 小米 MiMo 官方温暖音色
            val v1 = VoiceProfile(
                id = 1,
                name = "温柔月光 · 小米原生 (MiMo TTS)",
                cloudVoiceId = "mimo:default",
                localSamplePath = "",
                isDefault = true,
                createdAt = now - 3000
            )
            db.voiceProfileDao().insertVoiceProfile(v1)
        }
    }
}
