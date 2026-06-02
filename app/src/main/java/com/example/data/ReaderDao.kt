package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChapters(bookId: Int): List<Chapter>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<Chapter>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putChapter(chapter: Chapter): Long

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex LIMIT 1")
    suspend fun getChapter(bookId: Int, chapterIndex: Int): Chapter?

    @Query("SELECT * FROM translation_cache WHERE cacheKey = :cacheKey")
    suspend fun getTranslation(cacheKey: String): TranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTranslation(cache: TranslationCacheEntity)

    @Query("SELECT * FROM ui_translation_cache WHERE cacheKey = :cacheKey")
    suspend fun getUiTranslation(cacheKey: String): UiTranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUiTranslation(cache: UiTranslationCacheEntity)

    @Query("DELETE FROM translation_cache WHERE chapterId = :chapterId AND mode = :mode AND cacheKey != :activeCacheKey")
    suspend fun deleteStaleTranslations(chapterId: Long, mode: String, activeCacheKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgress)

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: Int): ReadingProgress?

    @Query("SELECT * FROM installed_sources ORDER BY name")
    suspend fun getInstalledSources(): List<InstalledSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putInstalledSource(source: InstalledSource)

    @Query("DELETE FROM installed_sources WHERE sourceId = :sourceId")
    suspend fun deleteInstalledSource(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDownloadTask(task: DownloadTask): Long

    @Query("SELECT * FROM download_tasks WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getDownloadTasks(bookId: Int): List<DownloadTask>

    @Query("DELETE FROM download_tasks WHERE bookId = :bookId")
    suspend fun deleteDownloadTasks(bookId: Int)

    @Query("SELECT * FROM download_batches WHERE id = :id")
    suspend fun getDownloadBatch(id: String): DownloadBatch?

    @Query("SELECT * FROM download_batches WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeDownloadBatches(bookId: Int): Flow<List<DownloadBatch>>

    @Query("SELECT * FROM download_batches WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getDownloadBatches(bookId: Int): List<DownloadBatch>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDownloadBatch(batch: DownloadBatch)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, paragraphAnchor")
    fun observeBookmarks(bookId: Int): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, paragraphAnchor")
    suspend fun getBookmarks(bookId: Int): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBookmark(bookmark: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM reader_notes WHERE bookId = :bookId ORDER BY chapterIndex, paragraphAnchor")
    fun observeNotes(bookId: Int): Flow<List<ReaderNote>>

    @Query("SELECT * FROM reader_notes WHERE bookId = :bookId ORDER BY chapterIndex, paragraphAnchor")
    suspend fun getNotes(bookId: Int): List<ReaderNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNote(note: ReaderNote): Long

    @Query("DELETE FROM reader_notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM dictionary_packages ORDER BY type")
    fun observeDictionaryPackages(): Flow<List<DictionaryPackage>>

    @Query("SELECT * FROM dictionary_packages ORDER BY type")
    suspend fun getDictionaryPackages(): List<DictionaryPackage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDictionaryPackage(dictionaryPackage: DictionaryPackage)

    @Query("DELETE FROM dictionary_packages WHERE type = :type")
    suspend fun deleteDictionaryPackage(type: String)
}
