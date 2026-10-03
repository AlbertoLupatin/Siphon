package app.siphon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.siphon.SiphonApp
import app.siphon.data.DownloadItem
import app.siphon.data.Status
import app.siphon.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the process alive while the queue has work, and mirrors progress into a notification. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Siphon:download")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            SiphonApp.get(this).downloads.cancelAll()
            return START_NOT_STICKY
        }
        val repo = SiphonApp.get(this).downloads
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(repo.items.value.filter { it.status.isActive }),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!started) {
            started = true
            scope.launch {
                repo.items
                    .map { list -> list.filter { it.status.isActive } }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { active ->
                        if (active.isEmpty()) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(active))
                            delay(500) // throttle: the system drops notification updates past ~10/s
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(active: List<DownloadItem>): Notification {
        val running = active.filter { it.status != Status.Queued }
        val first = running.firstOrNull() ?: active.firstOrNull()
        val title = when {
            active.isEmpty() -> "Siphon"
            active.size == 1 -> first!!.title
            else -> "${running.size} downloading · ${active.size - running.size} queued"
        }
        val text = first?.let { listOfNotNull(it.stage, it.speed, "%.0f%%".format(it.progress)).joinToString(" · ") }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val avg = if (running.isEmpty()) 0 else running.map { it.progress }.average().toInt()
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, avg, running.isEmpty() || running.all { it.status == Status.Processing })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Cancel all", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        private const val NOTIF_ID = 1
        private const val ACTION_CANCEL_ALL = "app.siphon.CANCEL_ALL"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }

        /** One-off "finished/failed" notification per item; tapping opens the file (or the app on failure). */
        fun notifyFinished(context: Context, item: DownloadItem) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannels(context)
            val out = item.outputs.firstOrNull()
            val target = if (item.status == Status.Done && out != null) {
                Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(out.uri), out.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } else Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, item.id.hashCode(), target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(if (item.status == Status.Done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (item.status == Status.Done) "Downloaded" else "Download failed")
                .setContentText(item.title)
                .setSubText(item.error)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), n)
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
