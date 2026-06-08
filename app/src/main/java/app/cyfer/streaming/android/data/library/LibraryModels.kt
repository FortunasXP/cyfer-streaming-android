package app.cyfer.streaming.android.data.library

import kotlinx.serialization.Serializable

/**
 * Persisted library state — watchlist entries + per-title playback
 * progress. Backed by [LibraryRepository] and lives in its own DataStore
 * (separate from AppSettings) so user content stays decoupled from
 * configuration.
 */
@Serializable
data class LibraryState(
    val watchlist: List<WatchlistEntry> = emptyList(),
    val progress: List<ProgressEntry> = emptyList(),
)

@Serializable
data class WatchlistEntry(
    val tmdbId: Int,
    val mediaType: String,        // "movie" | "tv"
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: String? = null,
    val voteAverage: Float = 0f,
    val addedAt: Long,
)

/**
 * Playback progress for one item. Movies key on `tmdbId + mediaType`;
 * TV episodes also use `season + episode` so each episode gets its own
 * entry while still rolling up under the parent series.
 */
@Serializable
data class ProgressEntry(
    val tmdbId: Int,
    val mediaType: String,        // "movie" | "tv"
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val seriesTmdbId: Int? = null,
    val seriesTitle: String? = null,
    val position: Double,
    val duration: Double,
    val updatedAt: Long,
) {
    /** Stable identity for grouping / dedup. */
    val key: String get() = buildString {
        append(mediaType)
        append(':')
        append(tmdbId)
        if (season != null) append(":S").append(season)
        if (episode != null) append(":E").append(episode)
    }

    /** Treat the title as "watched" once we cross the 85% mark. */
    val watched: Boolean get() = duration > 0 && (position / duration) >= WATCHED_RATIO

    companion object {
        const val WATCHED_RATIO = 0.85
        /** Don't save progress shorter than this — likely a misclick. */
        const val MIN_SAVE_SECONDS = 5.0
    }
}
