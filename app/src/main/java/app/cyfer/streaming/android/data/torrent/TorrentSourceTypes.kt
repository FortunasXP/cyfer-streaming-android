package app.cyfer.streaming.android.data.torrent

import app.cyfer.streaming.android.data.settings.TorrentSourceProviderId
import app.cyfer.streaming.android.data.settings.TorrentSourceProviderSettings

/** What kind of title we're searching for — drives provider selection and query shape. */
enum class TorrentMediaType { movie, tv, anime }

data class TorrentSourceSearchRequest(
    val title: String,
    val alternateTitles: List<String> = emptyList(),
    val mediaType: TorrentMediaType,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val limit: Int = 12,
    val providers: List<TorrentSourceProviderSettings>? = null,
)

data class TorrentSourceResult(
    val providerId: TorrentSourceProviderId,
    val providerName: String,
    val title: String,
    val magnet: String,
    val infoHash: String?,
    val detailUrl: String,
    val size: String?,
    val seeders: Int?,
    val leechers: Int?,
    val publishedAt: String?,
)

data class TorrentSourceError(
    val providerId: TorrentSourceProviderId,
    val providerName: String,
    val message: String,
)

/**
 * Cyfer's normalised stream object — same shape as the desktop's
 * `ResolvedStream` so the source picker UI can render identical badges.
 * The Android port doesn't need the full Stremio addon plumbing, just the
 * displayable fields.
 */
data class ResolvedStream(
    val addonName: String,
    val addonId: String,
    val playableUrl: String,
    val quality: StreamQuality,
    val releaseGroup: String? = null,
    val title: String,
    val size: String? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val infoHash: String? = null,
    /** Trackers harvested from the magnet, used by the on-device torrent engine. */
    val trackers: List<String> = emptyList(),
)

data class TorrentSearchOutcome(
    val streams: List<ResolvedStream>,
    val results: List<TorrentSourceResult>,
    val errors: List<TorrentSourceError>,
)
