package app.cyfer.streaming.android.ui.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.cyfer.streaming.android.data.settings.SettingsRepository
import app.cyfer.streaming.android.data.stremio.AddonSearchResult
import app.cyfer.streaming.android.data.stremio.getAddonStreams
import app.cyfer.streaming.android.data.torrent.ResolvedStream
import app.cyfer.streaming.android.data.torrent.TorrentMediaType
import app.cyfer.streaming.android.data.torrent.TorrentSourceError
import app.cyfer.streaming.android.data.torrent.TorrentSourceSearchRequest
import app.cyfer.streaming.android.data.torrent.searchCyferTorrentSources
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared source-search state + a small module-level TTL cache so two
 * Composables searching for the same title (the Details screen prefetch
 * and the SourcePickerSheet a few seconds later) share one network
 * round-trip.
 *
 * Behaviour mirrors the desktop's approach: kick off the lookup as soon
 * as the user opens a title, push results to the picker as each
 * provider completes, and never let a single provider error blow up
 * the whole view.
 */

data class AddonError(val name: String, val message: String)

data class SourceSearchState(
    /** True while *any* provider is still working. Goes false only once
     *  every torrent + addon job has completed. */
    val loading: Boolean = false,
    val streams: List<ResolvedStream> = emptyList(),
    val errors: List<TorrentSourceError> = emptyList(),
    val addonErrors: List<AddonError> = emptyList(),
    /** Count of providers still in flight — drives the "still searching N
     *  more" pill at the top of the picker. */
    val pendingProviders: Int = 0,
    /** Set ONLY when no provider was even attempted (no torrent sources
     *  enabled AND no addons installed) — used to render a real empty
     *  state instead of a misleading spinner. */
    val failure: String? = null,
)

private data class CachedEntry(val timestamp: Long, val state: SourceSearchState)

private val cache = ConcurrentHashMap<String, CachedEntry>()
private const val CACHE_TTL_MS = 5L * 60 * 1000

/**
 * Pulls pinned downloads matching the title + (for episodes) S/E. Each
 * match becomes a synthetic [ResolvedStream] with `addonId = "cyfer.downloaded"`
 * — picked up by the picker's provider-kind logic for a green
 * "DOWNLOADED" pill. The magnet URL routes through the existing
 * resolveLocalTorrent path; since the torrent is already in the engine,
 * `addMagnet` returns the cached session instantly and MPV plays with
 * no swarm spin-up.
 */
private suspend fun collectMatchingDownloads(
    context: android.content.Context,
    request: SourcePickerRequest,
): List<ResolvedStream> {
    val entries = app.cyfer.streaming.android.data.downloads.DownloadsRepository
        .get(context).entries.first()
    if (entries.isEmpty()) return emptyList()
    val tmdbId = request.tmdbId
    return entries.filter { entry ->
        val matchesTitle = tmdbId != null && entry.tmdbId == tmdbId
            || entry.title.equals(request.title, ignoreCase = true)
        val matchesEpisode = if (request.season != null && request.episode != null)
            entry.season == request.season && entry.episode == request.episode
        else true  // movies + season packs match on title alone
        matchesTitle && matchesEpisode
    }.map { entry ->
        ResolvedStream(
            addonName = "Downloaded",
            addonId = "cyfer.downloaded",
            playableUrl = entry.magnet,
            quality = app.cyfer.streaming.android.data.torrent.parseStreamQuality(entry.releaseTitle),
            title = entry.releaseTitle,
            size = formatBytes(entry.sizeBytes),
            seeders = null,
            leechers = null,
            infoHash = entry.infoHash,
            trackers = emptyList(),
        )
    }
}

private fun formatBytes(bytes: Long): String? {
    if (bytes <= 0) return null
    return when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MiB".format(bytes / (1024.0 * 1024))
        else -> null
    }
}

private fun cacheKey(request: SourcePickerRequest): String =
    "${request.title}|${request.alternateTitles.joinToString(",")}|${request.imdbId.orEmpty()}|${request.mediaType}|${request.season ?: ""}|${request.episode ?: ""}|${request.year.orEmpty()}"

/**
 * Imperative cache-warmer for prefetch. Runs the same pipeline as
 * [rememberSourceSearch] but without composition — caller is responsible
 * for the coroutine scope (typically the player's). When the user
 * finishes the current episode + lands on the Up Next picker, results
 * are already in [cache].
 */
suspend fun prefetchSourceSearch(
    context: android.content.Context,
    request: SourcePickerRequest,
) {
    val key = cacheKey(request)
    if (cachedIfFresh(key) != null) return
    val settings = SettingsRepository.get(context).settings.first()
    val enabledAddons = settings.installedAddons.filter { it.enabled }
    val mediaTypeToken = when (request.mediaType) {
        TorrentMediaType.tv -> "tv"
        TorrentMediaType.anime -> "anime"
        else -> "movie"
    }

    val streams = mutableListOf<ResolvedStream>()
    val errors = mutableListOf<TorrentSourceError>()
    val addonErrors = mutableListOf<AddonError>()

    coroutineScope {
        if (settings.torrentSourcesEnabled) {
            launch {
                runCatching {
                    searchCyferTorrentSources(
                        TorrentSourceSearchRequest(
                            title = request.title,
                            alternateTitles = request.alternateTitles,
                            mediaType = request.mediaType,
                            year = request.year,
                            season = request.season,
                            episode = request.episode,
                            providers = settings.torrentSourceProviders,
                            limit = 24,
                        ),
                    )
                }.onSuccess { out ->
                    synchronized(streams) { streams += out.streams }
                    synchronized(errors) { errors += out.errors }
                }
            }
        }
        if (!request.imdbId.isNullOrBlank() && enabledAddons.isNotEmpty()) {
            enabledAddons.forEach { addon ->
                launch {
                    runCatching {
                        getAddonStreams(
                            addons = listOf(addon),
                            stremioId = request.imdbId,
                            mediaType = mediaTypeToken,
                            season = request.season,
                            episode = request.episode,
                        )
                    }.onSuccess { res ->
                        synchronized(streams) { streams += res.streams }
                        synchronized(addonErrors) {
                            addonErrors += res.errors.map { AddonError(it.addon.name, it.error.orEmpty()) }
                        }
                    }
                }
            }
        }
    }

    val finalState = SourceSearchState(
        loading = false,
        streams = streams.toList(),
        errors = errors.toList(),
        addonErrors = addonErrors.toList(),
    )
    cache[key] = CachedEntry(System.currentTimeMillis(), finalState)
}

private fun cachedIfFresh(key: String): SourceSearchState? =
    cache[key]?.takeIf { System.currentTimeMillis() - it.timestamp < CACHE_TTL_MS }?.state

/**
 * Composable that returns the current [SourceSearchState] for the given
 * request, performing the search lazily and caching the result across
 * the screen. Pass `null` to clear / disable.
 *
 * Each provider (1 torrent search + N enabled addons) runs as its own
 * coroutine and pushes results into [state] as they arrive — the picker
 * stays interactive throughout, and one slow / failing addon never
 * holds up Torrentio results that are already in hand.
 */
@Composable
fun rememberSourceSearch(request: SourcePickerRequest?): SourceSearchState {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.get(context) }

    val key = request?.let(::cacheKey)
    var state by remember(key) {
        mutableStateOf(
            when {
                request == null -> SourceSearchState(loading = false)
                key != null && cachedIfFresh(key) != null -> cachedIfFresh(key)!!
                else -> SourceSearchState(loading = true)
            },
        )
    }

    // CRITICAL: only restart when the request actually changes. Previously
    // we keyed on settings.installedAddons / .torrentSourcesEnabled too —
    // but DataStore loads asynchronously, so the first compose got the
    // hard-coded defaults and the second got the user's real settings,
    // restarting (and cancelling all in-flight searches) ~170 ms in. The
    // result was every provider failing with "scope left the composition"
    // before anything could return. Read the latest settings once via a
    // suspending .first() instead.
    LaunchedEffect(key) {
        if (request == null || key == null) return@LaunchedEffect
        cachedIfFresh(key)?.let { state = it; return@LaunchedEffect }

        // ── Downloaded torrents always come first ────────────────
        // Match pinned downloads on (tmdbId, season, episode) so the
        // user sees "DOWNLOADED" rows at the top — playing them reuses
        // the already-on-disk pieces, so first-frame is near-instant.
        val downloadedStreams = runCatching {
            collectMatchingDownloads(context, request)
        }.getOrDefault(emptyList())
        if (downloadedStreams.isNotEmpty()) {
            state = state.copy(streams = downloadedStreams, loading = true)
        }

        val settings = settingsRepo.settings.first()

        // Tally the work we're about to fan out.
        val enabledAddons = settings.installedAddons.filter { it.enabled }
        val torrentEnabled = settings.torrentSourcesEnabled
        val addonsCanQuery = !request.imdbId.isNullOrBlank() && enabledAddons.isNotEmpty()
        val totalProviders =
            (if (torrentEnabled) 1 else 0) +
            (if (addonsCanQuery) enabledAddons.size else 0)

        android.util.Log.i(
            "SourceSearch",
            "→ search '${request.title}' alts=${request.alternateTitles}" +
                " media=${request.mediaType} season=${request.season} ep=${request.episode}" +
                " imdb=${request.imdbId} torrentEnabled=$torrentEnabled enabledAddons=${enabledAddons.size}",
        )

        if (totalProviders == 0) {
            // Nothing to query — surface a real failure instead of an
            // indefinite spinner.
            val message = when {
                !torrentEnabled && enabledAddons.isEmpty() ->
                    "No sources configured. Enable torrent providers or install a Stremio addon in Settings."
                !torrentEnabled && request.imdbId.isNullOrBlank() ->
                    "No torrent providers enabled, and this title has no IMDb id to query addons with."
                else -> "No sources configured for this title."
            }
            state = SourceSearchState(loading = false, failure = message)
            return@LaunchedEffect
        }

        // Reset for a fresh search.
        state = SourceSearchState(loading = true, pendingProviders = totalProviders)

        // A mutex protects the read-modify-write on `state` since N
        // launches will be appending in parallel.
        val stateLock = Mutex()

        suspend fun mergeTorrentResult(streams: List<ResolvedStream>, errors: List<TorrentSourceError>) {
            stateLock.withLock {
                val next = state.copy(
                    streams = state.streams + streams,
                    errors = state.errors + errors,
                    pendingProviders = (state.pendingProviders - 1).coerceAtLeast(0),
                )
                state = next.copy(loading = next.pendingProviders > 0)
                if (next.pendingProviders == 0) cache[key] = CachedEntry(System.currentTimeMillis(), state)
            }
        }

        suspend fun mergeAddonResult(result: AddonSearchResult) {
            stateLock.withLock {
                val next = state.copy(
                    streams = state.streams + result.streams,
                    addonErrors = state.addonErrors + result.errors.map {
                        AddonError(name = it.addon.name, message = it.error.orEmpty())
                    },
                    pendingProviders = (state.pendingProviders - 1).coerceAtLeast(0),
                )
                state = next.copy(loading = next.pendingProviders > 0)
                if (next.pendingProviders == 0) cache[key] = CachedEntry(System.currentTimeMillis(), state)
            }
        }

        coroutineScope {
            // ── Torrent search — one combined job (it already fans
            //    out internally across all enabled torrent providers) ──
            if (torrentEnabled) {
                launch {
                    val outcome = runCatching {
                        searchCyferTorrentSources(
                            TorrentSourceSearchRequest(
                                title = request.title,
                                alternateTitles = request.alternateTitles,
                                mediaType = request.mediaType,
                                year = request.year,
                                season = request.season,
                                episode = request.episode,
                                providers = settings.torrentSourceProviders,
                                limit = 24,
                            ),
                        )
                    }.getOrNull()
                    mergeTorrentResult(
                        streams = outcome?.streams.orEmpty(),
                        errors = outcome?.errors.orEmpty(),
                    )
                }
            }

            // ── Addon queries — one launch per addon so the fast ones
            //    show up before the slow ones (Torrentio is fast; some
            //    self-hosted addons take 5–10s and we don't want to
            //    block on them). ──
            if (addonsCanQuery) {
                val mediaTypeToken = when (request.mediaType) {
                    TorrentMediaType.tv -> "tv"
                    TorrentMediaType.anime -> "anime"
                    else -> "movie"
                }
                enabledAddons.forEach { addon ->
                    launch {
                        val result = runCatching {
                            getAddonStreams(
                                addons = listOf(addon),
                                stremioId = request.imdbId!!,
                                mediaType = mediaTypeToken,
                                season = request.season,
                                episode = request.episode,
                            )
                        }.getOrElse {
                            AddonSearchResult(
                                streams = emptyList(),
                                outcomes = listOf(
                                    app.cyfer.streaming.android.data.stremio.AddonStreamOutcome(
                                        addon = addon,
                                        streams = emptyList(),
                                        error = it.message ?: it.toString(),
                                    ),
                                ),
                            )
                        }
                        mergeAddonResult(result)
                    }
                }
            }
        }
    }

    return state
}
