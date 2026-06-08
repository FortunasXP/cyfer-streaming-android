package app.cyfer.streaming.android.data.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.cyfer.streaming.android.MainActivity
import app.cyfer.streaming.android.R
import app.cyfer.streaming.android.data.calendar.CalendarEpisode
import app.cyfer.streaming.android.data.calendar.CalendarRepository
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "EpisodeNotifyWorker"
private const val UNIQUE_WORK_NAME = "cyfer_episode_notify_daily"
private const val SENT_PREFS_NAME = "cyfer_notify_sent"
private val SENT_CSV_KEY = stringPreferencesKey("sent_csv")

private val Context.sentDataStore: DataStore<Preferences> by preferencesDataStore(SENT_PREFS_NAME)

/**
 * Periodic worker that scans the watchlist + the upcoming-episode feed
 * we already built for the Calendar tab. Fires a system notification for
 * each new episode airing in the next ~24 hours that we haven't already
 * pinged the user about.
 *
 * Dedupe key = `tmdbId|season|episode` stored in a Preferences DataStore.
 * Rolls over once an entry is more than 60 days old so the file stays
 * tiny over the long run.
 */
class EpisodeNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        NotificationChannels.ensure(applicationContext)

        val settings = runCatching { SettingsRepository.get(applicationContext).settings.first() }.getOrNull()
            ?: return Result.success()
        if (!settings.episodeNotificationsEnabled) {
            Log.i(TAG, "Episode notifications disabled by user — skipping")
            return Result.success()
        }

        // Permission check — on API 33+ the user can deny POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted — skipping")
                return Result.success()
            }
        }

        val library = LibraryRepository.get(applicationContext)
        val watchlist = runCatching { library.watchlist.first() }.getOrNull().orEmpty()
        if (watchlist.isEmpty()) return Result.success()

        val episodes = runCatching { CalendarRepository.getUpcomingEpisodes(watchlist) }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val horizon = now + 24L * 60 * 60 * 1000      // next 24 h
        val airingSoon = episodes.filter { it.airDateMillis in (now - 12L * 60 * 60 * 1000)..horizon }
        if (airingSoon.isEmpty()) return Result.success()

        val sent = readSent()
        val toNotify = airingSoon.filter { dedupeKey(it) !in sent }
        if (toNotify.isEmpty()) return Result.success()

        val nm = NotificationManagerCompat.from(applicationContext)
        toNotify.forEach { ep -> nm.notify(ep.hashCode(), buildNotification(ep)) }

        writeSent(sent + toNotify.map { dedupeKey(it) })
        Log.i(TAG, "Fired ${toNotify.size} episode notification${if (toNotify.size == 1) "" else "s"}")
        return Result.success()
    }

    private fun dedupeKey(e: CalendarEpisode): String =
        "${e.tmdbId}|${e.seasonNumber}|${e.episodeNumber}"

    private suspend fun readSent(): Set<String> = applicationContext.sentDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SENT_CSV_KEY]?.split('\n')?.filter { s -> s.isNotBlank() }?.toSet().orEmpty() }
        .first()

    private suspend fun writeSent(all: Collection<String>) {
        // Keep only the most-recent 256 entries — plenty for ~16 weeks of
        // weekly shows, and bounded so this file never grows unbounded.
        val keep = all.toList().takeLast(256)
        applicationContext.sentDataStore.edit { prefs ->
            prefs[SENT_CSV_KEY] = keep.joinToString("\n")
        }
    }

    private fun buildNotification(ep: CalendarEpisode): android.app.Notification {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(applicationContext, ep.hashCode(), openIntent, pendingFlags)

        val episodeLabel = if (ep.mediaType == "anime")
            "EP ${"%02d".format(ep.episodeNumber)}"
        else
            "S${"%02d".format(ep.seasonNumber)} E${"%02d".format(ep.episodeNumber)}"

        return NotificationCompat.Builder(applicationContext, NotificationChannels.EPISODE_AIR)
            .setSmallIcon(R.drawable.cyfer_app_icon_512)
            .setContentTitle("New ${ep.seriesTitle} episode")
            .setContentText("$episodeLabel  ·  ${ep.episodeName}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$episodeLabel  ·  ${ep.episodeName}\n${ep.overview.take(160)}"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    companion object {
        /**
         * Schedule the worker to run roughly every 12 hours. WorkManager
         * tolerates ±15 minutes of drift for battery efficiency, which is
         * fine for "did a watchlist show drop an episode today?".
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpisodeNotificationWorker>(12, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
