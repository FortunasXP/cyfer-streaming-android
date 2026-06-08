package app.cyfer.streaming.android.data.debrid

/**
 * Input to either [resolveRealDebrid] or [resolveTorBox]. Same shape as
 * the desktop `RealDebridResolveRequest` / `TorBoxResolveRequest` so the
 * call sites can stay symmetrical.
 */
data class DebridResolveRequest(
    /** Magnet URI. Both resolvers reject anything that isn't `magnet:`. */
    val source: String,
    val title: String? = null,
    val fileIdx: Int? = null,
    val fileMustInclude: String? = null,
    val filename: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val seriesTitle: String? = null,
    val timeoutMs: Long = 45_000L,
)

/** Output of either resolver — a direct HTTPS URL the player can stream. */
data class DebridResolvedMedia(
    val url: String,
    val filename: String?,
    val torrentId: String,
    val status: String,
    val progress: Int?,
    val bytes: Long?,
)

class DebridException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
