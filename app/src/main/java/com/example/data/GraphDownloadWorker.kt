package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class GraphDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL).orEmpty()
        val sha256 = inputData.getString(KEY_SHA256).orEmpty()
        try {
            validateUrl(url)
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Expected a SHA-256 checksum" }
            setForeground(foregroundInfo(0, 0))
            val partial = partialFile(sha256)
            partial.parentFile?.mkdirs()
            download(url, partial)
            GraphPackageManager(applicationContext).importGraph(partial.inputStream(), sha256, "download:$url")
            partial.delete()
            Result.success()
        } catch (error: IOException) {
            Log.w(TAG, "Graph download will retry", error)
            setProgress(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
            Result.retry()
        } catch (error: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
        }
    }

    private suspend fun download(url: String, partial: File) {
        var downloaded = partial.takeIf(File::isFile)?.length() ?: 0
        var response = execute(url, downloaded)
        if (downloaded > 0 && response.code != 206) {
            response.close()
            partial.delete()
            downloaded = 0
            response = execute(url, 0)
        }
        response.use {
            check(it.isSuccessful) { "Graph download failed with HTTP ${it.code}" }
            val body = it.body ?: error("Graph download body is empty")
            val total = totalBytes(it, downloaded, body.contentLength())
            FileOutputStream(partial, downloaded > 0).buffered().use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var nextProgress = downloaded
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded >= nextProgress) {
                            val progress = workDataOf(KEY_DOWNLOADED_BYTES to downloaded, KEY_TOTAL_BYTES to total)
                            setProgress(progress)
                            setForeground(foregroundInfo(downloaded, total))
                            nextProgress = downloaded + PROGRESS_INTERVAL_BYTES
                        }
                    }
                }
            }
            setProgress(workDataOf(KEY_DOWNLOADED_BYTES to downloaded, KEY_TOTAL_BYTES to total))
        }
    }

    private fun execute(url: String, downloaded: Long): Response {
        val request = Request.Builder().url(url).apply {
            if (downloaded > 0) header("Range", "bytes=$downloaded-")
        }.build()
        return client.newCall(request).execute()
    }

    private fun validateUrl(url: String) {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps || parsed.host in setOf("127.0.0.1", "localhost")) {
            "Graph download requires HTTPS, except for loopback development URLs"
        }
    }

    private fun totalBytes(response: Response, downloaded: Long, bodyLength: Long): Long {
        val contentRangeTotal = response.header("Content-Range")
            ?.substringAfterLast('/')
            ?.toLongOrNull()
        return contentRangeTotal ?: if (bodyLength >= 0) downloaded + bodyLength else 0
    }

    private fun partialFile(sha256: String) =
        File(applicationContext.cacheDir, "graph-download/$sha256.sqlite.part")

    private fun foregroundInfo(downloaded: Long, total: Long): ForegroundInfo {
        createNotificationChannel()
        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.example.R.mipmap.ic_launcher)
            .setContentTitle("Đang tải graph dịch DrDuc")
            .setContentText(if (total > 0) "$percent%" else "Đang kết nối...")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DrDuc graph download", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_DOWNLOADED_BYTES = "downloadedBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "drduc_graph_download"
        private const val NOTIFICATION_ID = 1123
        private const val PROGRESS_INTERVAL_BYTES = 2L * 1024L * 1024L
        private const val TAG = "GraphDownloadWorker"
    }
}
