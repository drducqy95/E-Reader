package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Book::class,
        Chapter::class,
        InstalledSource::class,
        ContentCache::class,
        TranslationCacheEntity::class,
        UiTranslationCacheEntity::class,
        DownloadTask::class,
        ReadingProgress::class,
        DownloadBatch::class,
        Bookmark::class,
        ReaderNote::class,
        DictionaryPackage::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readerDao(): ReaderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ereader_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chapters (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterIndex INTEGER NOT NULL, title TEXT NOT NULL, sourceUrl TEXT NOT NULL, rawContent TEXT NOT NULL, sourceRevision TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapters_bookId_chapterIndex ON chapters (bookId, chapterIndex)")
                db.execSQL("CREATE TABLE IF NOT EXISTS installed_sources (sourceId TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, payloadJson TEXT NOT NULL, revision TEXT NOT NULL, installedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS content_cache (cacheKey TEXT NOT NULL PRIMARY KEY, filePath TEXT NOT NULL, checksum TEXT NOT NULL, sourceRevision TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS translation_cache (cacheKey TEXT NOT NULL PRIMARY KEY, chapterId INTEGER NOT NULL, mode TEXT NOT NULL, translatedText TEXT NOT NULL, graphVersion TEXT NOT NULL, overlayVersion INTEGER NOT NULL, providerMode TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS download_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterIndex INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reading_progress (bookId INTEGER NOT NULL PRIMARY KEY, chapterIndex INTEGER NOT NULL, chapterOffset INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_cache ADD COLUMN configHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE translation_cache ADD COLUMN sourceChecksum TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE translation_cache ADD COLUMN hookVersion TEXT NOT NULL DEFAULT 'none'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN status TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN totalChapters INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN latestChapter TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN contentKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN checksum TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN downloadStatus TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED'")
                db.execSQL("ALTER TABLE chapters ADD COLUMN downloadedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE translation_cache ADD COLUMN dictionaryVersion TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("CREATE TABLE IF NOT EXISTS download_batches (id TEXT NOT NULL PRIMARY KEY, bookId INTEGER NOT NULL, firstChapter INTEGER NOT NULL, lastChapter INTEGER NOT NULL, status TEXT NOT NULL, downloadedChapters INTEGER NOT NULL, totalChapters INTEGER NOT NULL, attempts INTEGER NOT NULL, concurrency INTEGER NOT NULL, maxRetries INTEGER NOT NULL, lastError TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterIndex INTEGER NOT NULL, paragraphAnchor INTEGER NOT NULL, excerpt TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId_chapterIndex ON bookmarks (bookId, chapterIndex)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reader_notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterIndex INTEGER NOT NULL, paragraphAnchor INTEGER NOT NULL, excerpt TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reader_notes_bookId_chapterIndex ON reader_notes (bookId, chapterIndex)")
                db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_packages (type TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL, version TEXT NOT NULL, checksum TEXT NOT NULL, entryCount INTEGER NOT NULL, status TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ui_translation_cache (cacheKey TEXT NOT NULL PRIMARY KEY, fieldType TEXT NOT NULL, sourceText TEXT NOT NULL, translatedText TEXT NOT NULL, graphVersion TEXT NOT NULL, overlayVersion INTEGER NOT NULL, dictionaryVersion TEXT NOT NULL, hookVersion TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
    }
}
