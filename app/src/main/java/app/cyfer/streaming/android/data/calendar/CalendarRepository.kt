package app.cyfer.streaming.android.data.calendar

import android.util.Log
import app.cyfer.streaming.android.data.library.WatchlistEntry
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val TAG = "CalendarRepo"

/**
 * One TV episode resolved for the calendar — mirrors the desktop's
 * [CalendarEpisode] shape, trimmed to the fields the Android UI needs.
 */
data class CalendarEpisode(
    val tmdbId: Int,          // show id
    val mediaType: String,    // "tv" | "anime"
    val seriesTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeName: String,
    val overview: String,
    val airDate: String,      // YYYY-MM-DD
    val airDateMillis: Long,
    val stillUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
)

/** Bounds for the calendar fetch — a rolling window so we don't pull the
 *  entire history of a 20-season show. */
private const val PAST_DAYS = 14L
private const val FUTURE_DAYS = 90L

object CalendarRepository {

    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Pull every upcoming + recently-aired episode for each TV show in
     * the watchlist. Returns one [CalendarEpisode] per episode, deduped
     * and sorted by air date ascending.
     */
    suspend fun getUpcomingEpisodes(watchlist: List<WatchlistEntry>): List<CalendarEpisode> =
        withContext(Dispatchers.IO) {
            val tvEntries = watchlist.filter { it.mediaType == "tv" || it.mediaType == "anime" }
            if (tvEntries.isEmpty()) return@withContext emptyList()

            val nowMs = System.currentTimeMillis()
            val pastBound = nowMs - PAST_DAYS * 24 * 60 * 60 * 1000
            val futureBound = nowMs + FUTURE_DAYS * 24 * 60 * 60 * 1000

            val results = coroutineScope {
                tvEntries.map { entry ->
                    async {
                        runCatching { fetchEpisodesForShow(entry, pastBound, futureBound) }
                            .getOrElse {
                                Log.w(TAG, "Episodes fetch failed for tmdbId=${entry.tmdbId}: ${it.message}")
                                emptyList()
                            }
                    }
                }.awaitAll().flatten()
            }

            results
                .distinctBy { "${it.tmdbId}|${it.seasonNumber}|${it.episodeNumber}" }
                .sortedBy { it.airDateMillis }
        }

    private suspend fun fetchEpisodesForShow(
        entry: WatchlistEntry,
        pastBound: Long,
        futureBound: Long,
    ): List<CalendarEpisode> {
        val details = TmdbRepository.getTvDetails(entry.tmdbId)
        val seasons = details.seasons.orEmpty().filter { it.season_number > 0 }
        // Only walk seasons that could possibly contain a date in our window:
        // every season with a known air_date from now-PAST_DAYS onward, plus
        // the most recent two seasons (covers the "currently airing" case
        // where TMDb hasn't filled in the season air_date yet).
        val recentSeasons = seasons.takeLast(2).toSet()
        val candidateSeasons = seasons.filter { season ->
            recentSeasons.contains(season) ||
                season.air_date?.let { d ->
                    runCatching { ymd.parse(d)?.time }.getOrNull()
                        ?.let { it in pastBound..futureBound }
                } == true
        }
        if (candidateSeasons.isEmpty()) return emptyList()

        return coroutineScope {
            candidateSeasons.map { season ->
                async {
                    runCatching { TmdbRepository.getTvSeason(entry.tmdbId, season.season_number) }
                        .getOrElse { emptyList() }
                        .mapNotNull { ep ->
                            val airDate = ep.air_date?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val parsedMs = runCatching { ymd.parse(airDate)?.time }.getOrNull() ?: return@mapNotNull null
                            if (parsedMs < pastBound || parsedMs > futureBound) return@mapNotNull null
                            CalendarEpisode(
                                tmdbId = entry.tmdbId,
                                mediaType = entry.mediaType,
                                seriesTitle = entry.title,
                                seasonNumber = season.season_number,
                                episodeNumber = ep.episode_number,
                                episodeName = ep.name.orEmpty().ifBlank { "Episode ${ep.episode_number}" },
                                overview = ep.overview.orEmpty(),
                                airDate = airDate,
                                airDateMillis = parsedMs,
                                stillUrl = ep.stillUrl,
                                posterUrl = entry.posterUrl,
                                backdropUrl = entry.backdropUrl,
                            )
                        }
                }
            }.awaitAll().flatten()
        }
    }

    /** Coarse-grained "day key" — YYYY-MM-DD in the device's local
     *  timezone, used for grouping in the UI. */
    fun dayKey(millis: Long, calendar: Calendar = Calendar.getInstance()): String {
        calendar.timeInMillis = millis
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
