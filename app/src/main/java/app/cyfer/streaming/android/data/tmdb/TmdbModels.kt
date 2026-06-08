package app.cyfer.streaming.android.data.tmdb

data class TmdbResponse(
    val page: Int = 0,
    val results: List<TmdbItem> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

data class TmdbItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Float = 0f,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val media_type: String? = null,
    val genre_ids: List<Int> = emptyList()
) {
    val displayTitle: String
        get() = title ?: name ?: "Untitled"

    val displayYear: String
        get() {
            val date = release_date ?: first_air_date ?: ""
            return if (date.length >= 4) date.substring(0, 4) else ""
        }

    val posterUrl: String?
        get() = poster_path?.let { "${TmdbConfig.POSTER_W500}$it" }

    val backdropUrl: String?
        get() = backdrop_path?.let { "${TmdbConfig.BACKDROP_W1280}$it" }

    val ratingFormatted: String
        get() = String.format("%.1f", vote_average)
}

data class TmdbDetailResponse(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Float = 0f,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val runtime: Int? = null,
    val episode_run_time: List<Int>? = null,
    val tagline: String? = null,
    val status: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
    val similar: TmdbResponse? = null,
    val number_of_seasons: Int? = null,
    val number_of_episodes: Int? = null,
    val seasons: List<TmdbSeason>? = null,
    /** Present on /movie/{id} directly; on TV it's exposed via [external_ids]. */
    val imdb_id: String? = null,
    val external_ids: TmdbExternalIds? = null,
    val images: TmdbImages? = null
) {
    /** Stremio addons want the IMDb id (`tt12345678`) as the stream id. */
    val stremioId: String? get() = imdb_id?.takeIf { it.isNotBlank() }
        ?: external_ids?.imdb_id?.takeIf { it.isNotBlank() }

    /** Best logo for this title — TMDb logos use a transparent PNG suited
     *  for layering over a backdrop. Falls back to null if no logos exist. */
    val logoUrl: String? get() = images?.bestLogoUrl

    val displayTitle: String get() = title ?: name ?: "Untitled"
    val displayYear: String get() {
        val date = release_date ?: first_air_date ?: ""
        return if (date.length >= 4) date.substring(0, 4) else ""
    }
    val posterUrl: String? get() = poster_path?.let { "${TmdbConfig.POSTER_W500}$it" }
    val backdropUrl: String? get() = backdrop_path?.let { "${TmdbConfig.BACKDROP_W1280}$it" }
    val ratingFormatted: String get() = String.format("%.1f", vote_average)
    val runtimeFormatted: String get() {
        val mins = runtime ?: episode_run_time?.firstOrNull() ?: return ""
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    val genreText: String get() = genres.joinToString(", ") { it.name }
}

data class TmdbGenre(val id: Int = 0, val name: String = "")

data class TmdbGenreList(val genres: List<TmdbGenre> = emptyList())

data class TmdbExternalIds(
    val imdb_id: String? = null,
    val tvdb_id: Int? = null
)

data class TmdbImages(
    val logos: List<TmdbLogo> = emptyList(),
    val backdrops: List<TmdbLogo> = emptyList(),
    val posters: List<TmdbLogo> = emptyList()
) {
    /**
     * Mirrors `pickTMDbLogo` from `src/lib/api-bridge.ts`. Prefers English,
     * then language-neutral, then by weighted TMDb vote_count + size.
     */
    val bestLogoUrl: String?
        get() = logos
            .filter { !it.file_path.isNullOrBlank() }
            .map { logo ->
                val lang = (logo.iso_639_1 ?: "").lowercase()
                val langScore = when (lang) { "en" -> 3; "" -> 2; else -> 1 }
                val voteScore = (logo.vote_count) * 100 + logo.vote_average * 10
                val sizeScore = (logo.width + logo.height).toDouble()
                logo to (langScore * 100_000L + voteScore.toLong() + (sizeScore / 100).toLong())
            }
            .maxByOrNull { it.second }
            ?.first?.file_path
            ?.let { "${TmdbConfig.IMAGE_BASE}original$it" }
}

data class TmdbLogo(
    val file_path: String? = null,
    val iso_639_1: String? = null,
    val vote_count: Int = 0,
    val vote_average: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val aspect_ratio: Double = 0.0
)

data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

data class TmdbCast(
    val id: Int = 0,
    val name: String = "",
    val character: String? = null,
    val profile_path: String? = null
) {
    val profileUrl: String? get() = profile_path?.let { "${TmdbConfig.POSTER_W500}$it" }
}

data class TmdbCrew(
    val id: Int = 0,
    val name: String = "",
    val job: String? = null,
    val department: String? = null
)

// ──────────────────────── Person / actor ────────────────────────

data class TmdbPerson(
    val id: Int = 0,
    val name: String = "",
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val place_of_birth: String? = null,
    val profile_path: String? = null,
    val known_for_department: String? = null,
    val popularity: Float = 0f,
) {
    val profileUrl: String? get() = profile_path?.let { "${TmdbConfig.POSTER_W500}$it" }
}

/** A single credit on `/person/{id}/combined_credits` — covers both
 *  movie credits (with `title`/`release_date`) and TV (`name`/`first_air_date`). */
data class TmdbPersonCredit(
    val id: Int = 0,
    val media_type: String? = null,    // "movie" | "tv"
    val title: String? = null,
    val name: String? = null,
    val character: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Float = 0f,
    val popularity: Float = 0f,
) {
    val displayTitle: String get() = title ?: name ?: "Untitled"
    val posterUrl: String? get() = poster_path?.let { "${TmdbConfig.POSTER_W500}$it" }
    val displayYear: String get() {
        val date = release_date ?: first_air_date ?: ""
        return if (date.length >= 4) date.substring(0, 4) else ""
    }
    val airDateMillis: Long get() {
        val date = release_date ?: first_air_date ?: return 0L
        // Approximate: parse as YYYY-MM-DD timezone-naively for sort only.
        val year = date.take(4).toIntOrNull() ?: return 0L
        val month = date.substring(5..6).toIntOrNull() ?: 1
        val day = date.substring(8..9).toIntOrNull() ?: 1
        val cal = java.util.Calendar.getInstance()
        cal.set(year, month - 1, day, 0, 0, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

data class TmdbCombinedCredits(
    val id: Int = 0,
    val cast: List<TmdbPersonCredit> = emptyList(),
    val crew: List<TmdbPersonCredit> = emptyList(),
)

data class TmdbSeason(
    val id: Int = 0,
    val season_number: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    val episode_count: Int? = null,
    val poster_path: String? = null,
    val air_date: String? = null
) {
    val posterUrl: String? get() = poster_path?.let { "${TmdbConfig.POSTER_W500}$it" }
}

/** Response shape for `/tv/{id}/season/{n}`. */
data class TmdbSeasonResponse(
    val id: Int = 0,
    val season_number: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val episodes: List<TmdbEpisode> = emptyList()
)

data class TmdbEpisode(
    val id: Int = 0,
    val episode_number: Int = 0,
    val season_number: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val runtime: Int? = null,
    val still_path: String? = null,
    val vote_average: Float = 0f
) {
    val displayTitle: String get() = name ?: "Episode $episode_number"
    val stillUrl: String? get() = still_path?.let { "${TmdbConfig.BACKDROP_W1280}$it" }
    val runtimeFormatted: String get() {
        val mins = runtime ?: return ""
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
