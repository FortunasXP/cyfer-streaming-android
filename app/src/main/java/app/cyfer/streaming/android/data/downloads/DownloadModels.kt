package app.cyfer.streaming.android.data.downloads

import kotlinx.serialization.Serializable

/**
 * One user-pinned download. Persisted to DataStore so it survives across
 * player close, app exit, and process kill — distinguishing it from the
 * ephemeral torrents we start to stream + tear down when the player
 * closes (those aren't recorded here).
 */
@Serializable
data class DownloadEntry(
    /** btih hex string (40 chars, lower-case). Used as the primary key. */
    val infoHash: String,
    /** Magnet URI — used to re-add the torrent on app restart. */
    val magnet: String,
    /** Display title — TMDb movie/show name. */
    val title: String,
    /** Release file title — the gnarly "Movie.2024.2160p.WEB-DL..." string. */
    val releaseTitle: String,
    /** Poster + backdrop URLs so the Downloads screen can render artwork. */
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** "movie" | "tv" | "anime". */
    val mediaType: String,
    val tmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val addedAt: Long,
    /** Last known bytes the user has cached. Refreshed as the torrent
     *  progresses. Zero on initial save. */
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.Queued,
)

@Serializable
enum class DownloadStatus {
    Queued,        // Added to the engine, waiting for metadata / first pieces
    Checking,      // libtorrent is re-hashing existing files (cold start without resume data)
    Downloading,   // Actively pulling pieces
    Paused,        // User-paused
    Completed,     // 100% downloaded
    Error,         // Engine reported a torrent_error / metadata failure
}

@Serializable
data class DownloadsState(
    val entries: List<DownloadEntry> = emptyList(),
)
