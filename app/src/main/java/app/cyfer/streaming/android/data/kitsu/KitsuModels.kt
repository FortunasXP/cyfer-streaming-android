package app.cyfer.streaming.android.data.kitsu

/**
 * Cyfer's flattened anime view — mirrors the desktop's `AnimeTitle` from
 * `src/lib/types.ts`. We only carry the fields the UI actually needs, so
 * decode failures (Kitsu adds new keys often) stay fatal-free.
 */
data class AnimeTitle(
    val id: Int,
    val kitsuId: String,
    val title: String,
    /** Kitsu's `titles.en` — English localised title (e.g. "Frieren: Beyond Journey's End"). */
    val englishTitle: String? = null,
    /** Kitsu's `titles.en_jp` — romaji (e.g. "Sousou no Frieren"). What
     *  anime trackers index by, more often than not. */
    val romajiTitle: String? = null,
    /** Kitsu's `titles.ja_jp` — original Japanese characters
     *  (e.g. "葬送のフリーレン"). Kept for display + JP-language tracker
     *  matching, but useless for romaji-indexed sites. */
    val originalTitle: String? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val releaseDate: String? = null,
    val year: String? = null,
    val voteAverage: Float? = null,
    val popularity: Int? = null,
    val genres: List<String> = emptyList(),
    val adult: Boolean = false,
    val episodeCount: Int? = null,
    val episodeLength: Int? = null,
    val status: String? = null,
    val subtype: String? = null,
    val season: String? = null,
    /** "series" | "movie" — derived from subtype. */
    val animeKind: String = "series",
) {
    /** Every distinct title we know for this anime, in tracker-friendly
     *  order: romaji first (most matches), then English, then Japanese. */
    val allSearchableTitles: List<String> get() = listOfNotNull(
        title.takeIf { it.isNotBlank() },
        romajiTitle?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
        englishTitle?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) && !it.equals(romajiTitle, ignoreCase = true) },
        originalTitle?.takeIf { it.isNotBlank() },
    )
}

data class AnimeHomeFeed(
    /** Hero rotator — top of Kitsu's weekly trending, so the page
     *  visibly changes as Kitsu's data does (the old feed used all-time
     *  popularity, which froze the hero on the same evergreens). */
    val spotlight: List<AnimeTitle> = emptyList(),
    val trending: List<AnimeTitle> = emptyList(),
    val airing: List<AnimeTitle> = emptyList(),
    /** The current broadcast season (e.g. everything airing Spring
     *  2026), most-followed first. */
    val seasonal: List<AnimeTitle> = emptyList(),
    /** Announced/upcoming titles, most-followed first. */
    val upcoming: List<AnimeTitle> = emptyList(),
    /** Best of the current year — the year's popular titles re-ranked
     *  by rating (popularity floor keeps 5-vote obscurities out). */
    val topRated: List<AnimeTitle> = emptyList(),
    /** e.g. "Spring 2026" — eyebrow for the seasonal row. */
    val seasonLabel: String = "",
    /** e.g. "2026" — label for the best-of-year row. */
    val yearLabel: String = "",
)

data class AnimeCatalogResult(
    val items: List<AnimeTitle> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
)

data class AnimeEpisode(
    val id: Int,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
)
