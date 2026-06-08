package app.cyfer.streaming.android.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Cyfer's notification channels. Android groups notifications by channel
 * — distinct channels let the user mute/unmute categories independently
 * (Settings → Apps → Cyfer → Notifications).
 */
object NotificationChannels {

    const val EPISODE_AIR = "cyfer_episode_air"
    const val DOWNLOADS = "cyfer_downloads"

    fun ensure(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return

        if (nm.getNotificationChannel(EPISODE_AIR) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    EPISODE_AIR,
                    "New episodes",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Get pinged when a show on your watchlist drops a new episode."
                },
            )
        }
        if (nm.getNotificationChannel(DOWNLOADS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DOWNLOADS,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Background progress notifications for saved downloads."
                },
            )
        }
    }
}
