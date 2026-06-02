package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chapters", indices = [Index(value = ["bookId", "chapterIndex"], unique = true)])
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val title: String,
    val sourceUrl: String = "",
    val rawContent: String,
    val contentKey: String = "",
    val checksum: String = "",
    val downloadStatus: String = "NOT_DOWNLOADED",
    val downloadedAt: Long = 0L,
    val sourceRevision: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "installed_sources")
data class InstalledSource(
    @PrimaryKey val sourceId: String,
    val type: String,
    val name: String,
    val payloadJson: String,
    val revision: String = "",
    val installedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "content_cache")
data class ContentCache(
    @PrimaryKey val cacheKey: String,
    val filePath: String,
    val checksum: String,
    val sourceRevision: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "translation_cache")
data class TranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val chapterId: Long,
    val mode: String,
    val translatedText: String,
    val graphVersion: String,
    val overlayVersion: Long,
    val providerMode: String,
    val configHash: String,
    val sourceChecksum: String,
    val hookVersion: String,
    val dictionaryVersion: String = "none",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "ui_translation_cache")
data class UiTranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val fieldType: String,
    val sourceText: String,
    val translatedText: String,
    val graphVersion: String,
    val overlayVersion: Long,
    val dictionaryVersion: String,
    val hookVersion: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "download_tasks")
data class DownloadTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val status: String,
    val attempts: Int = 0,
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey val bookId: Int,
    val chapterIndex: Int,
    val chapterOffset: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "download_batches")
data class DownloadBatch(
    @PrimaryKey val id: String,
    val bookId: Int,
    val firstChapter: Int,
    val lastChapter: Int,
    val status: String,
    val downloadedChapters: Int = 0,
    val totalChapters: Int,
    val attempts: Int = 0,
    val concurrency: Int = 2,
    val maxRetries: Int = 3,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["bookId", "chapterIndex"])])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val paragraphAnchor: Int,
    val excerpt: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reader_notes", indices = [Index(value = ["bookId", "chapterIndex"])])
data class ReaderNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val paragraphAnchor: Int,
    val excerpt: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "dictionary_packages")
data class DictionaryPackage(
    @PrimaryKey val type: String,
    val fileName: String,
    val version: String,
    val checksum: String,
    val entryCount: Int,
    val status: String,
    val updatedAt: Long = System.currentTimeMillis()
)
