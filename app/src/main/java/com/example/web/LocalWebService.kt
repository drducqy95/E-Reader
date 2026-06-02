package com.example.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.drduc.web.SecureLocalWebServer
import com.example.EReaderApplication
import com.example.R
import fi.iki.elonen.NanoHTTPD

class LocalWebService : Service() {
    private var server: SecureLocalWebServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as EReaderApplication
        val api = EReaderWebApi(this, app.database, app.translationOrchestrator, app.graphPackageManager)
        val started = PORT_RANGE.firstNotNullOfOrNull { port ->
            runCatching {
                SecureLocalWebServer(port = port, allowLan = false, api = api).also {
                    it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
            }.getOrNull()?.let { port to it }
        }
        if (started == null) {
            setEnabled(this, false)
            stopSelf()
            return
        }
        val (port, localServer) = started
        server = localServer
        activePort = port
        setEnabled(this, true)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("DrDuc local web console")
                .setContentText("http://127.0.0.1:$port/admin/")
                .setOngoing(true)
                .build()
        )
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        activePort = null
        setEnabled(this, false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Local web console", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "drduc_local_web"
        private const val NOTIFICATION_ID = 1122
        private const val PREFERENCES = "drduc_local_web"
        private const val ENABLED = "enabled"
        private val PORT_RANGE = 1122..1132
        @Volatile
        var activePort: Int? = null
            private set

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        }
    }
}
