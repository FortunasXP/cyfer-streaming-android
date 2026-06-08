package app.cyfer.streaming.android.data.settings

import app.cyfer.streaming.android.data.stremio.InstalledAddon
import kotlinx.serialization.Serializable

@Serializable
enum class TorrentSourceProviderId {
    rargb,
    `1337x`,
    nyaa,
    erairaws,
    toonshub,
    tokyotosho,
    bangumi,
    anidex,
    kickass,
    tpb,
    torrentgalaxy,
    magnetdl,
}

/**
 * Anime-only torrent providers — Nyaa-family trackers that exclusively
 * index Japanese fansub releases. When the user asks for an anime title
 * we restrict the search to these, and when they ask for a movie / TV
 * show we skip them. This avoids cross-pollution (Nyaa won't have
 * "Interstellar"; TPB won't have "Frieren E14") and keeps the picker
 * relevant.
 */
val ANIME_ONLY_PROVIDERS: Set<TorrentSourceProviderId> = setOf(
    TorrentSourceProviderId.nyaa,
    TorrentSourceProviderId.erairaws,
    TorrentSourceProviderId.toonshub,
    TorrentSourceProviderId.tokyotosho,
    TorrentSourceProviderId.bangumi,
    TorrentSourceProviderId.anidex,
)

@Serializable
data class TorrentSourceProviderSettings(
    val id: TorrentSourceProviderId,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean,
)

@Serializable
data class TorrentRssSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Hardware video decoding mode passed to MPV via the `hwdec` option.
 *  - [AUTO]  → `mediacodec` (fast path; lets MediaCodec own the buffers)
 *  - [COPY]  → `mediacodec-copy` (decoded frames copied to sysram; safer
 *    on devices with broken zero-copy surfaces — slightly higher CPU)
 *  - [OFF]   → `no` (pure software decode; max compatibility, expensive)
 */
@Serializable
enum class HardwareDecodingMode { AUTO, COPY, OFF }

fun HardwareDecodingMode.mpvOption(): String = when (this) {
    HardwareDecodingMode.AUTO -> "mediacodec"
    HardwareDecodingMode.COPY -> "mediacodec-copy"
    HardwareDecodingMode.OFF -> "no"
}

@Serializable
data class AppSettings(
    /** TMDb v4 read-only bearer token. Empty means fall back to bundled default. */
    val tmdbReadToken: String = "",

    val realDebridEnabled: Boolean = false,
    val realDebridApiToken: String = "",

    val torboxEnabled: Boolean = false,
    val torboxApiToken: String = "",

    /** Master toggle for the public torrent providers. */
    val torrentSourcesEnabled: Boolean = true,
    val torrentSourceProviders: List<TorrentSourceProviderSettings> = DEFAULT_TORRENT_SOURCE_PROVIDERS,

    /** Private-tracker RSS feeds the user has added. Off by default. */
    val torrentRssSources: List<TorrentRssSource> = emptyList(),

    /** User-installed Stremio addons (Torrentio, Cinemeta, OpenSubtitles, etc.). */
    val installedAddons: List<InstalledAddon> = emptyList(),

    /**
     * Anime release groups the user prefers. Used to up-rank matches and
     * to drive the Erai-raws / ToonsHub release-group provider queries.
     */
    val animeFansubGroupPreferences: List<String> = emptyList(),

    /**
     * If RD/TorBox can't return a stream, fall back to the on-device
     * torrent engine. Requires the local torrent engine to be available.
     */
    val debridFallbackToLocalTorrent: Boolean = true,

    /** Hardware video decoding mode applied to MPV. */
    val hardwareDecoding: HardwareDecodingMode = HardwareDecodingMode.AUTO,

    /**
     * When the user crosses ~95% on a TV/anime episode, show an "Up Next"
     * card and auto-advance to the next episode after a 10s countdown.
     * Off-by-default — the user opts in.
     */
    val autoplayNextEpisode: Boolean = false,

    /**
     * At ~50% played, warm the source-picker cache for the next episode
     * so the picker shows results instantly when the user finishes.
     * Only fires if [autoplayNextEpisode] is also on — no point caching
     * something the user hasn't agreed to auto-play.
     */
    val prefetchNextEpisode: Boolean = false,

    /**
     * Background nightly job that pings the user when a watchlist show
     * drops a new episode (next 24 h window, deduped).
     */
    val episodeNotificationsEnabled: Boolean = true,
)

/**
 * Default torrent provider list — mirrors the desktop
 * `DEFAULT_TORRENT_SOURCE_PROVIDERS` in `src/lib/torrent-source-types.ts`.
 * Keep the lists in sync when adding or retiring providers.
 */
val DEFAULT_TORRENT_SOURCE_PROVIDERS: List<TorrentSourceProviderSettings> = listOf(
    TorrentSourceProviderSettings(TorrentSourceProviderId.rargb,         "RARBG-compatible", "https://rargb.to",            enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.nyaa,          "Nyaa",             "https://nyaa.si",             enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.erairaws,      "Erai-raws",        "https://nyaa.si",             enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.toonshub,      "ToonsHub",         "https://nyaa.si",             enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.tokyotosho,    "TokyoTosho",       "https://www.tokyotosho.info", enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.bangumi,       "Bangumi.moe",      "https://bangumi.moe",         enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.anidex,        "AniDex",           "https://anidex.info",         enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.kickass,       "KickassTorrents",  "https://katcr.to",            enabled = false),
    TorrentSourceProviderSettings(TorrentSourceProviderId.tpb,           "The Pirate Bay",   "https://apibay.org",          enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.torrentgalaxy, "TorrentGalaxy",    "https://torrentgalaxy.one",   enabled = true),
    TorrentSourceProviderSettings(TorrentSourceProviderId.magnetdl,      "MagnetDL",         "https://magnetdl.co",         enabled = true),
)

/**
 * Reconcile a deserialised provider list with the latest defaults — new
 * providers introduced in this build appear with their default settings;
 * retired providers drop off. Mirrors the desktop
 * `normaliseTorrentSourceProviders` function.
 */
fun normaliseTorrentSourceProviders(
    saved: List<TorrentSourceProviderSettings>,
): List<TorrentSourceProviderSettings> {
    val byId = saved.associateBy { it.id }
    return DEFAULT_TORRENT_SOURCE_PROVIDERS.map { fallback ->
        val match = byId[fallback.id]
        if (match == null) fallback
        else TorrentSourceProviderSettings(
            id = fallback.id,
            name = match.name.ifBlank { fallback.name },
            baseUrl = match.baseUrl.trimEnd('/').ifBlank { fallback.baseUrl },
            enabled = match.enabled,
        )
    }
}
