package app.cyfer.streaming.android.data.torrent.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.cyfer.streaming.android.R

/**
 * Keeps the [TorrentEngine] + [TorrentStreamingServer] alive in the
 * background while a torrent is being streamed.
 *
 * Foreground type is `mediaPlayback` because the work is "we are
 * delivering bytes to a video player" — Android 14+ requires the
 * specific type and `mediaPlayback` is the closest match.
 *
 * Started via [start]; stopped via [stop]. The service is idempotent —
 * multiple resolve calls are fine, and [stop] becomes a no-op when no
 * torrents are active.
 */
class TorrentForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val notification = buildNotification("Preparing torrent stream…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        TorrentEngine.start(this)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cyfer:TorrentEngine")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) {
            updateNotification("Streaming · $title")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        TorrentStreamingServer.stopShared()
        TorrentEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private fun buildNotification(content: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cyfer torrent engine")
            .setContentText(content)
            .setSmallIcon(R.drawable.cyfer_app_icon_512)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(content))
    }

    companion object {
        private const val CHANNEL_ID = "cyfer_torrent_engine"
        private const val NOTIFICATION_ID = 0xCFE9
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60 * 1000  // 1h
        const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String? = null) {
            val intent = Intent(context, TorrentForegroundService::class.java).apply {
                title?.let { putExtra(EXTRA_TITLE, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorrentForegroundService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Torrent streaming",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notification shown while Cyfer streams from a torrent in the background."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
