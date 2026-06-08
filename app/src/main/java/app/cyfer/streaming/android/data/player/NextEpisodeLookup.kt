package app.cyfer.streaming.android.data.player

import android.util.Log
import app.cyfer.streaming.android.data.tmdb.TmdbRepository

private const val TAG = "NextEpisode"

/**
 * The next episode after a (tmdbId, season, episode) tuple. Returns null
 * when we're at the end of the show — meaning the player should NOT
 * trigger autoplay.
 */
data class NextEpisode(
    val tmdbId: Int,
    val season: Int,
    val episode: Int,
    val name: String,
    val stillUrl: String? = null,
)

object NextEpisodeLookup {

    /**
     * Walk the current season for `episode + 1`; if not found, hop to
     * the next season's first episode. Returns null if neither exists
     * (genuinely the last episode of the series).
     */
    suspend fun findNext(
        tmdbId: Int,
        currentSeason: Int,
        currentEpisode: Int,
    ): NextEpisode? {
        // Same season, next episode
        runCatching {
            val episodes = TmdbRepository.getTvSeason(tmdbId, currentSeason)
            val next = episodes.firstOrNull { it.episode_number == currentEpisode + 1 }
            if (next != null) {
                return NextEpisode(
                    tmdbId = tmdbId,
                    season = currentSeason,
                    episode = next.episode_number,
                    name = next.name?.takeIf { it.isNotBlank() } ?: "Episode ${next.episode_number}",
                    stillUrl = next.stillUrl,
                )
            }
        }.onFailure { Log.w(TAG, "Same-season lookup failed for $tmdbId S${currentSeason}: ${it.message}") }

        // Next season's first episode
        runCatching {
            val details = TmdbRepository.getTvDetails(tmdbId)
            val nextSeasonNum = details.seasons
                ?.filter { it.season_number > currentSeason && it.season_number > 0 }
                ?.minByOrNull { it.season_number }
                ?.season_number
                ?: return null
            val nextSeason = TmdbRepository.getTvSeason(tmdbId, nextSeasonNum)
            val first = nextSeason.firstOrNull { it.episode_number == 1 } ?: nextSeason.firstOrNull()
            if (first != null) {
                return NextEpisode(
                    tmdbId = tmdbId,
                    season = nextSeasonNum,
                    episode = first.episode_number,
                    name = first.name?.takeIf { it.isNotBlank() } ?: "Episode ${first.episode_number}",
                    stillUrl = first.stillUrl,
                )
            }
        }.onFailure { Log.w(TAG, "Next-season lookup failed for $tmdbId: ${it.message}") }

        return null
    }
}
