package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.EReaderApplication
import com.example.R
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class VbookDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID).orEmpty()
        if (batchId.isBlank()) return Result.failure(workDataOf(KEY_ERROR to "Missing batchId"))
        val app = applicationContext as EReaderApplication
        val reader = app.database.readerDao()
        var batch = reader.getDownloadBatch(batchId) ?: return Result.failure(workDataOf(KEY_ERROR to "Batch not found"))
        val book = app.database.bookDao().getBookSnapshot(batch.bookId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Book not found"))
        return try {
            val chapters = app.onlineLibraryService.ensureToc(book)
                .filter { it.chapterIndex in batch.firstChapter..batch.lastChapter }
            batch = batch.copy(status = DownloadCoordinator.STATUS_RUNNING, totalChapters = chapters.size)
            reader.putDownloadBatch(batch)
            setForeground(foregroundInfo(batch.downloadedChapters, chapters.size))
            val progressMutex = Mutex()
            var completed = 0
            coroutineScope {
                val semaphore = Semaphore(batch.concurrency.coerceIn(1, MAX_CONCURRENCY))
                chapters.map { chapter ->
                    async {
                        semaphore.withPermit {
                            val current = reader.getDownloadBatch(batchId)
                            if (current?.status in setOf(DownloadCoordinator.STATUS_PAUSED, DownloadCoordinator.STATUS_CANCELLED)) return@withPermit
                            if (chapter.rawContent.isBlank() && !app.chapterContentStore.hasValid(chapter.contentKey, chapter.checksum)) {
                                retryChapter(current?.attempts ?: 0, batch.maxRetries) { app.onlineLibraryService.ensureContent(book, chapter) }
                            }
                            progressMutex.withLock {
                                completed++
                                val latest = checkNotNull(reader.getDownloadBatch(batchId))
                                reader.putDownloadBatch(latest.copy(downloadedChapters = completed, updatedAt = System.currentTimeMillis()))
                                setProgress(workDataOf(KEY_DOWNLOADED_CHAPTERS to completed, KEY_TOTAL_CHAPTERS to chapters.size))
                                setForeground(foregroundInfo(completed, chapters.size))
                            }
                        }
                    }
                }.awaitAll()
            }
            val latest = checkNotNull(reader.getDownloadBatch(batchId))
            if (latest.status == DownloadCoordinator.STATUS_RUNNING) {
                reader.putDownloadBatch(latest.copy(status = DownloadCoordinator.STATUS_COMPLETED, downloadedChapters = chapters.size, updatedAt = System.currentTimeMillis()))
            }
            Result.success(workDataOf(KEY_DOWNLOADED_CHAPTERS to completed, KEY_TOTAL_CHAPTERS to chapters.size))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            reader.putDownloadBatch(batch.copy(status = DownloadCoordinator.STATUS_QUEUED, attempts = batch.attempts + 1, lastError = error.message.orEmpty(), updatedAt = System.currentTimeMillis()))
            Result.retry()
        } catch (error: Exception) {
            reader.putDownloadBatch(batch.copy(status = DownloadCoordinator.STATUS_FAILED, attempts = batch.attempts + 1, lastError = error.message ?: error.javaClass.simpleName, updatedAt = System.currentTimeMillis()))
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
        }
    }

    private suspend fun retryChapter(attemptOffset: Int, maxRetries: Int, block: suspend () -> Unit) {
        var last: Exception? = null
        repeat(maxRetries.coerceIn(1, MAX_RETRIES)) { attempt ->
            try {
                block()
                return
            } catch (error: Exception) {
                last = error
                delay((attemptOffset + attempt + 1) * 500L)
            }
        }
        throw last ?: IOException("Chapter download failed")
    }

    private fun foregroundInfo(downloaded: Int, total: Int): ForegroundInfo {
        createNotificationChannel()
        val percent = if (total > 0) downloaded * 100 / total else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Đang tải truyện online")
            .setContentText(if (total > 0) "$downloaded/$total chương" else "Đang tải mục lục...")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Online book downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val KEY_BATCH_ID = "batchId"
        const val KEY_DOWNLOADED_CHAPTERS = "downloadedChapters"
        const val KEY_TOTAL_CHAPTERS = "totalChapters"
        const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 3
        private const val MAX_CONCURRENCY = 6
        private const val CHANNEL_ID = "vbook_download"
        private const val NOTIFICATION_ID = 1124
    }
}
