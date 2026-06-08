package app.cyfer.streaming.android.data.downloads

import android.content.Context
import android.util.Log
import app.cyfer.streaming.android.data.torrent.engine.TorrentEngine
import app.cyfer.streaming.android.data.torrent.engine.TorrentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DownloadsCoordinator"

/**
 * Glue between [DownloadsRepository] (persistence) and [TorrentEngine]
 * (live torrent state).
 *
 *  • [restoreOnStartup] re-adds every pinned magnet on app launch so
 *    paused/queued downloads pick up where they left off.
 *  • [pin] starts a brand new download from the source picker.
 *  • A background poller writes live progress back into the repository
 *    every 2 seconds so the Downloads screen UI is reactive.
 */
object DownloadsCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollerJob: Job? = null
    private val started = AtomicBoolean(false)

    /**
     * Boot the engine, re-add every pinned magnet, and start polling the
     * library for live progress updates. Safe to call multiple times.
     */
    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val repo = DownloadsRepository.get(appCtx)

        scope.launch {
            val state = repo.state.first()
            if (state.entries.isEmpty()) {
                Log.i(TAG, "No pinned downloads to restore")
                return@launch
            }
            Log.i(TAG, "Restoring ${state.entries.size} pinned downloads")
            TorrentEngine.start(appCtx)
            TorrentForegroundService.start(appCtx)
            for (entry in state.entries) {
                TorrentEngine.pin(entry.infoHash)
                runCatching { TorrentEngine.addMagnet(entry.magnet) }
                    .onFailure { Log.w(TAG, "Failed to re-add ${entry.infoHash}: ${it.message}") }
            }
        }

        // Live-progress poller — wakes every 2 s while there are pinned
        // downloads, writes the snapshot back into DataStore. Every 15
        // ticks (~30 s) we also ask libtorrent to dump fastresume data
        // so an unclean kill never loses more than half a minute of
        // verified pieces.
        pollerJob?.cancel()
        pollerJob = scope.launch {
            var tick = 0
            while (true) {
                delay(2_000L)
                tick++
                val entries = runCatching { repo.entries.first() }.getOrNull() ?: continue
                if (entries.isEmpty()) continue
                for (entry in entries) {
                    val snap = TorrentEngine.snapshot(entry.infoHash) ?: continue
                    val nextStatus = when {
                        snap.checking -> DownloadStatus.Checking
                        snap.progress >= 0.999f -> DownloadStatus.Completed
                        snap.paused -> DownloadStatus.Paused
                        snap.downloadRate > 0 -> DownloadStatus.Downloading
                        snap.downloadedBytes > 0 -> DownloadStatus.Paused
                        else -> DownloadStatus.Queued
                    }
                    repo.updateProgress(
                        infoHash = entry.infoHash,
                        downloadedBytes = snap.downloadedBytes,
                        sizeBytes = snap.totalSizeBytes,
                        status = nextStatus,
                    )
                    // Periodic resume-data snapshot so cold starts are
                    // instant the next time around.
                    if (tick % 15 == 0) {
                        TorrentEngine.requestSaveResume(entry.infoHash)
                    }
                }
            }
        }
    }

    /** Pin a new download. Starts the torrent engine if it wasn't already. */
    suspend fun pin(context: Context, entry: DownloadEntry) {
        val appCtx = context.applicationContext
        TorrentEngine.start(appCtx)
        TorrentForegroundService.start(appCtx)
        TorrentEngine.pin(entry.infoHash)
        runCatching { TorrentEngine.addMagnet(entry.magnet) }
            .onFailure { Log.w(TAG, "addMagnet failed for ${entry.infoHash}: ${it.message}") }
        DownloadsRepository.get(appCtx).pin(entry)
    }

    /** Pause / resume on a pinned torrent. */
    fun pause(infoHash: String) { TorrentEngine.pause(infoHash) }
    fun resume(infoHash: String) { TorrentEngine.resume(infoHash) }

    /** Remove the download (kills engine handle + deletes files + unpins). */
    suspend fun remove(context: Context, infoHash: String) {
        TorrentEngine.remove(infoHash, deleteFiles = true)
        DownloadsRepository.get(context.applicationContext).unpin(infoHash)
    }

    fun shutdown() {
        // Best-effort: flush every torrent's resume data before we tear
        // down the engine. SAVE_RESUME_DATA is async so the alert may
        // arrive moments later — the listener writes it synchronously
        // when it does.
        runCatching { TorrentEngine.saveAllResumeData() }
        pollerJob?.cancel()
        scope.cancel()
        started.set(false)
    }
}
