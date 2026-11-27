package com.galeria.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.galeria.app.data.Models
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Downloads the on-device AI models once, with a progress notification, and stops when they are all
 * here. It keeps going if the app is closed meanwhile; an interrupted file resumes next time.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.models_channel), NotificationManager.IMPORTANCE_LOW))
        val first = note(0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        scope.launch {
            Models.progress.sample(1000).collectLatest { p -> if (!p.ready) nm.notify(ID, note(p.done, p.total)) }
        }
        scope.launch {
            val ok = runCatching { Models.download(applicationContext) }.getOrDefault(false)
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!ok) nm.notify(ID, Notification.Builder(this@ModelDownloadService, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.models_paused))
                .setContentText(getString(R.string.models_paused_sub)).setContentIntent(open()).setAutoCancel(true).build())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun note(done: Long, total: Long): Notification {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.models_downloading))
            .setContentText(if (total > 0) "%d%% · %.0f / %.0f MB".format(pct, done / 1e6, total / 1e6) else getString(R.string.models_starting))
            .setProgress(100, pct, total == 0L)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open())
            .build()
    }

    override fun onDestroy() { scope.cancel(); running = false; super.onDestroy() }

    companion object {
        private const val CHANNEL = "models"
        private const val ID = 4101
        @Volatile private var running = false

        /** Starts the download if a model is missing (call while the app is on screen). */
        fun startIfNeeded(context: Context) {
            if (running || Models.missing(context).isEmpty()) return
            runCatching { context.startForegroundService(Intent(context, ModelDownloadService::class.java)) }
                .onFailure { android.util.Log.w("Photos", "model download start", it) }
        }
    }
}
