package com.example.data

import android.content.Context
import androidx.work.WorkInfo
import com.example.EReaderApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID

data class VbookDownloadSnapshot(
    val id: UUID,
    val state: WorkInfo.State,
    val downloadedChapters: Int,
    val totalChapters: Int,
    val error: String
)

object VbookDownloadScheduler {
    fun enqueue(context: Context, bookId: Int): UUID = runBlocking {
        val app = context.applicationContext as EReaderApplication
        val chapters = app.database.readerDao().getChapters(bookId)
        require(chapters.isNotEmpty()) { "Book does not contain chapters" }
        enqueueRange(context, bookId, chapters.first().chapterIndex, chapters.last().chapterIndex)
    }

    suspend fun enqueueRange(context: Context, bookId: Int, firstChapter: Int, lastChapter: Int): UUID {
        val app = context.applicationContext as EReaderApplication
        return UUID.fromString(app.downloadCoordinator.enqueue(bookId, firstChapter, lastChapter).id)
    }

    fun snapshots(context: Context, bookId: Int): List<VbookDownloadSnapshot> = runBlocking {
        val app = context.applicationContext as EReaderApplication
        app.downloadCoordinator.list(bookId).map {
            VbookDownloadSnapshot(
                id = UUID.fromString(it.id),
                state = when (it.status) {
                    DownloadCoordinator.STATUS_COMPLETED -> WorkInfo.State.SUCCEEDED
                    DownloadCoordinator.STATUS_FAILED -> WorkInfo.State.FAILED
                    DownloadCoordinator.STATUS_CANCELLED -> WorkInfo.State.CANCELLED
                    DownloadCoordinator.STATUS_RUNNING -> WorkInfo.State.RUNNING
                    else -> WorkInfo.State.ENQUEUED
                },
                downloadedChapters = it.downloadedChapters,
                totalChapters = it.totalChapters,
                error = it.lastError
            )
        }
    }
}
