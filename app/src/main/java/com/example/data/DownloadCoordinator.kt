package com.example.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class DownloadCoordinator(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val reader = database.readerDao()

    suspend fun enqueue(bookId: Int, firstChapter: Int, lastChapter: Int): DownloadBatch {
        require(bookId > 0) { "Expected a book id" }
        require(firstChapter >= 0 && lastChapter >= firstChapter) { "Invalid chapter range" }
        val batch = DownloadBatch(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            firstChapter = firstChapter,
            lastChapter = lastChapter,
            status = STATUS_QUEUED,
            totalChapters = lastChapter - firstChapter + 1,
            concurrency = settingsRepository.downloadConcurrency.first(),
            maxRetries = settingsRepository.downloadRetries.first()
        )
        reader.putDownloadBatch(batch)
        enqueueWorker(batch.id)
        return batch
    }

    suspend fun pause(batchId: String) {
        update(batchId) { it.copy(status = STATUS_PAUSED, updatedAt = System.currentTimeMillis()) }
        WorkManager.getInstance(context).cancelUniqueWork(workName(batchId))
    }

    suspend fun resume(batchId: String) {
        update(batchId) { it.copy(status = STATUS_QUEUED, lastError = "", updatedAt = System.currentTimeMillis()) }
        enqueueWorker(batchId)
    }

    suspend fun cancel(batchId: String) {
        update(batchId) { it.copy(status = STATUS_CANCELLED, updatedAt = System.currentTimeMillis()) }
        WorkManager.getInstance(context).cancelUniqueWork(workName(batchId))
    }

    fun observe(bookId: Int): Flow<List<DownloadBatch>> = reader.observeDownloadBatches(bookId)

    suspend fun list(bookId: Int): List<DownloadBatch> = reader.getDownloadBatches(bookId)

    private suspend fun update(batchId: String, transform: (DownloadBatch) -> DownloadBatch) {
        val batch = reader.getDownloadBatch(batchId) ?: error("Download batch not found: $batchId")
        reader.putDownloadBatch(transform(batch))
    }

    private fun enqueueWorker(batchId: String) {
        val request = OneTimeWorkRequestBuilder<VbookDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(VbookDownloadWorker.KEY_BATCH_ID to batchId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(batchId), ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_FAILED = "FAILED"

        fun workName(batchId: String) = "book-download-$batchId"
    }
}
