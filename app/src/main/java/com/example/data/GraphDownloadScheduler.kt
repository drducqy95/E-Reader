package com.example.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

data class GraphDownloadSnapshot(
    val id: UUID,
    val state: WorkInfo.State,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String
)

object GraphDownloadScheduler {
    const val UNIQUE_WORK_NAME = "drduc-graph-download"

    fun enqueue(context: Context, url: String, sha256: String): UUID {
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Expected a SHA-256 checksum" }
        val parsedUrl = url.toHttpUrl()
        val constraints = Constraints.Builder().apply {
            if (parsedUrl.host !in setOf("127.0.0.1", "localhost")) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val request = OneTimeWorkRequestBuilder<GraphDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                Data.Builder()
                    .putString(GraphDownloadWorker.KEY_URL, url)
                    .putString(GraphDownloadWorker.KEY_SHA256, sha256.lowercase())
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun enqueueProduction(context: Context): UUID {
        val config = ProductionExpansionConfig.fromBuild()
        require(config.configured) { "Production graph expansion URL and SHA-256 are not configured" }
        return enqueue(context, config.url, config.sha256)
    }

    fun snapshots(context: Context): List<GraphDownloadSnapshot> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().map {
            GraphDownloadSnapshot(
                id = it.id,
                state = it.state,
                downloadedBytes = it.progress.getLong(GraphDownloadWorker.KEY_DOWNLOADED_BYTES, 0),
                totalBytes = it.progress.getLong(GraphDownloadWorker.KEY_TOTAL_BYTES, 0),
                error = it.outputData.getString(GraphDownloadWorker.KEY_ERROR).orEmpty()
            )
        }
}
