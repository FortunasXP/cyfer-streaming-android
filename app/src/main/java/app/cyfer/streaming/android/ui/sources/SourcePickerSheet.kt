package app.cyfer.streaming.android.ui.sources

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.debrid.DebridResolveRequest
import app.cyfer.streaming.android.data.debrid.resolveStreamingUrl
import app.cyfer.streaming.android.data.settings.AppSettings
import app.cyfer.streaming.android.data.settings.SettingsRepository
import app.cyfer.streaming.android.data.torrent.ResolvedStream
import app.cyfer.streaming.android.data.torrent.StreamResolution
import app.cyfer.streaming.android.data.torrent.TorrentMediaType
import app.cyfer.streaming.android.data.torrent.TorrentSourceError
import app.cyfer.streaming.android.data.torrent.cleanReleaseTitle
import app.cyfer.streaming.android.data.torrent.extractChannelLayout
import app.cyfer.streaming.android.data.torrent.streamDisplayTagsForRow
import app.cyfer.streaming.android.ui.common.CyferChip
import app.cyfer.streaming.android.ui.common.CyferOutlinePill
import app.cyfer.streaming.android.ui.common.CyferTagPill
import app.cyfer.streaming.android.ui.common.TechLogoBadge
import app.cyfer.streaming.android.ui.theme.*
import kotlinx.coroutines.launch

data class SourcePickerRequest(
    val title: String,
    val year: String?,
    val mediaType: TorrentMediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** IMDb id (`tt12345678`) — only used to query Stremio addons. */
    val imdbId: String? = null,
    /** TMDb backdrop URL — shown on the player loading splash. */
    val backdropUrl: String? = null,
    /** TMDb id — carried into PlayerRequest so progress can be keyed. */
    val tmdbId: Int? = null,
    val posterUrl: String? = null,
    /** Romaji / native-language titles fed into the torrent search as
     *  alternate query candidates. Critical for anime — Kitsu hands us
     *  the English title but Nyaa indexes "Sousou no Frieren", not
     *  "Frieren: Beyond Journey's End". */
    val alternateTitles: List<String> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    request: SourcePickerRequest,
    onDismiss: () -> Unit,
    onResolved: (url: String, title: String, initialPosition: Double) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val libraryRepo = remember { app.cyfer.streaming.android.data.library.LibraryRepository.get(context) }
    val progress by libraryRepo.progress.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadsRepo = remember { app.cyfer.streaming.android.data.downloads.DownloadsRepository.get(context) }
    val downloadEntries by downloadsRepo.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    // Lookup: info-hash → (fractionDone, isComplete). Streams that match
    // a pinned download render an App Store-style progress ring instead
    // of the plain download icon.
    val downloadProgressByHash = remember(downloadEntries) {
        downloadEntries.associate { e ->
            val pct = if (e.sizeBytes > 0)
                (e.downloadedBytes.toFloat() / e.sizeBytes).coerceIn(0f, 1f) else 0f
            // Treat Checking as "complete enough to play" — the bytes
            // are on disk, libtorrent's just verifying them.
            val effectivelyComplete =
                e.status == app.cyfer.streaming.android.data.downloads.DownloadStatus.Completed ||
                    e.status == app.cyfer.streaming.android.data.downloads.DownloadStatus.Checking
            e.infoHash to (pct to effectivelyComplete)
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Resume offset for this title — look up the matching ProgressEntry.
    val resumePosition: Double = remember(request, progress) {
        if (request.tmdbId == null) return@remember 0.0
        val mt = when (request.mediaType) {
            TorrentMediaType.tv -> "tv"
            TorrentMediaType.anime -> "anime"
            else -> "movie"
        }
        val match = progress.firstOrNull {
            it.tmdbId == request.tmdbId &&
                it.mediaType == mt &&
                it.season == request.season &&
                it.episode == request.episode
        }
        if (match != null && !match.watched) match.position else 0.0
    }

    // Search state lives in [rememberSourceSearch] — shared with the
    // Details screen's prefetch via a module-level TTL cache, so the
    // sheet renders instantly if the prefetch has already completed.
    val search = rememberSourceSearch(request)

    var resolvingStreamId by remember(request) { mutableStateOf<String?>(null) }
    var resolveError by remember(request) { mutableStateOf<String?>(null) }

    // Filter state — provider kind tab + resolution chip, mirrors the
    // desktop source picker chrome.
    var providerFilter by remember(request) { mutableStateOf(ProviderFilter.All) }
    var resolutionFilter by remember(request) { mutableStateOf(ResolutionFilter.All) }

    // Counts for the filter labels.
    val providerCounts = remember(search.streams) {
        val torrents = search.streams.count { it.addonId.startsWith("cyfer.torrent.") }
        val direct = search.streams.count { it.addonId.startsWith("cyfer.direct") }
        val addons = search.streams.size - torrents - direct
        mapOf(
            ProviderFilter.All to search.streams.size,
            ProviderFilter.Torrents to torrents,
            ProviderFilter.Addons to addons,
            ProviderFilter.Direct to direct,
        )
    }
    val byProvider = remember(search.streams, providerFilter) {
        when (providerFilter) {
            ProviderFilter.All -> search.streams
            ProviderFilter.Torrents -> search.streams.filter { it.addonId.startsWith("cyfer.torrent.") }
            ProviderFilter.Addons -> search.streams.filter {
                !it.addonId.startsWith("cyfer.torrent.") && !it.addonId.startsWith("cyfer.direct")
            }
            ProviderFilter.Direct -> search.streams.filter { it.addonId.startsWith("cyfer.direct") }
        }
    }
    val resolutionCounts = remember(byProvider) {
        mapOf(
            ResolutionFilter.All to byProvider.size,
            ResolutionFilter.K4 to byProvider.count { it.quality.resolution == StreamResolution.K4 },
            ResolutionFilter.P1080 to byProvider.count { it.quality.resolution == StreamResolution.P1080 },
            ResolutionFilter.P720 to byProvider.count { it.quality.resolution == StreamResolution.P720 },
        )
    }
    val visibleStreams = remember(byProvider, resolutionFilter) {
        when (resolutionFilter) {
            ResolutionFilter.All -> byProvider
            ResolutionFilter.K4 -> byProvider.filter { it.quality.resolution == StreamResolution.K4 }
            ResolutionFilter.P1080 -> byProvider.filter { it.quality.resolution == StreamResolution.P1080 }
            ResolutionFilter.P720 -> byProvider.filter { it.quality.resolution == StreamResolution.P720 }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CyferDarkSurface,
        contentColor = CyferWhite,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        // Use a stable 85%-of-screen height instead of a free-floating
        // `heightIn(240..720)` — letting the sheet's intrinsic size grow
        // as items load made the LazyColumn lurch each time a provider
        // came back. Fixed-height sheet = smooth scroll throughout.
        val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = (screenHeight * 0.55f).coerceAtLeast(320.dp), max = screenHeight * 0.92f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = request.title,
                color = CyferWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                request.year?.let { append(it).append("  ·  ") }
                append(when (request.mediaType) {
                    TorrentMediaType.movie -> "Movie"
                    TorrentMediaType.tv -> "TV"
                    TorrentMediaType.anime -> "Anime"
                })
                if (request.season != null && request.episode != null) {
                    append("  ·  S${"%02d".format(request.season)}E${"%02d".format(request.episode)}")
                }
            }
            Text(sub, color = CyferTextSecondary, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(4.dp))

            when {
                // Hard failure — nothing was even attempted. Show the
                // failure copy verbatim so the user knows what to fix.
                search.failure != null && search.streams.isEmpty() -> ErrorBox(search.failure)

                // First-paint spinner — no streams yet AND providers
                // are still in flight.
                search.loading && search.streams.isEmpty() -> CenteredSpinner("Searching sources…")

                // Search finished with zero streams — empty state.
                // Surface every diagnostic we have: title + alternates the
                // pipeline queried with, errors per provider (network /
                // 5xx), and addon errors. Without this the user has no way
                // to tell whether nothing matched or every provider failed.
                !search.loading && search.streams.isEmpty() -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "No sources found.",
                            color = CyferWhite,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // What we actually searched for — this is usually
                        // where the bug hides ("oh, it's searching the
                        // English title and Nyaa only indexes romaji").
                        Text(
                            text = "Searched: “${request.title}”" +
                                if (request.alternateTitles.isNotEmpty())
                                    " + " + request.alternateTitles.joinToString(", ") { "“$it”" }
                                else "",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (search.errors.isNotEmpty()) {
                            Text(
                                text = "Torrent providers reporting errors:",
                                color = CyferTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            search.errors.forEach { e ->
                                Text(
                                    text = "  • ${e.providerName}: ${e.message.take(120)}",
                                    color = CyferError,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (search.addonErrors.isNotEmpty()) {
                            Text(
                                text = "Stremio addons reporting errors:",
                                color = CyferTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            search.addonErrors.forEach { e ->
                                Text(
                                    text = "  • ${e.name}: ${e.message.take(120)}",
                                    color = CyferError,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (search.errors.isEmpty() && search.addonErrors.isEmpty()) {
                            Text(
                                text = "Every provider responded successfully but returned no matches. The query format may be wrong for these trackers, or the title doesn't exist on the public indexes Cyfer uses.",
                                color = CyferTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                else -> {
                    // Slim pending pill — visible while providers are still
                    // arriving. Wrapped in AnimatedVisibility so the
                    // appear/disappear is a smooth slide instead of a hard
                    // pop that jolts every chip below down 40 dp.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = search.loading && search.pendingProviders > 0,
                        enter = androidx.compose.animation.expandVertically() +
                            androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() +
                            androidx.compose.animation.fadeOut(),
                    ) {
                        PendingProvidersPill(count = search.pendingProviders)
                    }
                    // ── Provider filter tabs (with counts) ──────────
                    // `animateContentSize` smooths the width change when a
                    // chip's count grows from "0" → "12" → "123" — without
                    // it the whole row jumps by a few pixels as each new
                    // provider's results land.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .animateContentSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderFilter.values().forEach { p ->
                            CyferChip(
                                label = p.label,
                                count = providerCounts[p] ?: 0,
                                selected = providerFilter == p,
                                onClick = {
                                    providerFilter = p
                                    resolutionFilter = ResolutionFilter.All
                                },
                            )
                        }
                    }

                    // ── Resolution chips ────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .animateContentSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ResolutionFilter.values().forEach { r ->
                            CyferChip(
                                label = r.label,
                                count = resolutionCounts[r] ?: 0,
                                selected = resolutionFilter == r,
                                onClick = { resolutionFilter = r },
                            )
                        }
                    }

                    resolveError?.let { Text(it, color = CyferError, style = MaterialTheme.typography.bodySmall) }

                    if (visibleStreams.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No sources match the active filters.",
                                color = CyferTextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // weight(1f, fill = false) caused jank at the end of
                        // the list because the LazyColumn would re-measure on
                        // every item resolve. fill = true gives it a stable
                        // height and the scroll smooths out.
                        modifier = Modifier.weight(1f),
                    ) {
                        // Index in the key so duplicate addon entries (Torrentio
                        // often returns multiple sources at the same quality tier
                        // sharing addonId + title) don't crash the LazyColumn.
                        itemsIndexed(
                            visibleStreams,
                            key = { idx, s -> "${s.addonId}|${s.playableUrl.hashCode()}|$idx" },
                        ) { _, stream ->
                            // Look up download state for this stream (if any).
                            val streamHash = stream.infoHash
                                ?: parseInfoHashFromMagnet(stream.playableUrl)
                            val downloadState = streamHash?.let { downloadProgressByHash[it] }
                            StreamRow(
                                stream = stream,
                                resolving = resolvingStreamId == stream.playableUrl,
                                enabled = resolvingStreamId == null,
                                settings = settings,
                                downloadProgress = downloadState?.first,
                                downloadComplete = downloadState?.second == true,
                                // Hide the save-offline button on rows that
                                // are already pinned (cyfer.downloaded). And
                                // only show it for magnet sources — direct
                                // HTTP streams aren't keepable.
                                onSaveOffline = if (
                                    stream.addonId != "cyfer.downloaded" &&
                                    stream.playableUrl.startsWith("magnet:", ignoreCase = true)
                                ) {
                                    {
                                        scope.launch {
                                            val infoHash = stream.infoHash ?: parseInfoHashFromMagnet(stream.playableUrl)
                                            if (infoHash != null) {
                                                app.cyfer.streaming.android.data.downloads.DownloadsCoordinator.pin(
                                                    context = context,
                                                    entry = app.cyfer.streaming.android.data.downloads.DownloadEntry(
                                                        infoHash = infoHash,
                                                        magnet = stream.playableUrl,
                                                        title = request.title,
                                                        releaseTitle = cleanReleaseTitle(stream.title).ifBlank { stream.title },
                                                        posterUrl = request.posterUrl,
                                                        backdropUrl = request.backdropUrl,
                                                        mediaType = when (request.mediaType) {
                                                            TorrentMediaType.tv -> "tv"
                                                            TorrentMediaType.anime -> "anime"
                                                            else -> "movie"
                                                        },
                                                        tmdbId = request.tmdbId,
                                                        season = request.season,
                                                        episode = request.episode,
                                                        addedAt = System.currentTimeMillis(),
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                } else null,
                                onClick = {
                                    resolvingStreamId = stream.playableUrl
                                    resolveError = null
                                    // Direct HTTP/HTTPS streams from addons play as-is —
                                    // only magnet / .torrent sources need debrid resolution.
                                    val needsDebrid = stream.playableUrl.startsWith("magnet:", ignoreCase = true)
                                        || Regex("\\.torrent(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(stream.playableUrl)
                                    if (!needsDebrid) {
                                        onResolved(stream.playableUrl, request.title, resumePosition)
                                    } else {
                                        scope.launch {
                                            runCatching {
                                                resolveStreamingUrl(
                                                    context,
                                                    settings,
                                                    DebridResolveRequest(
                                                        source = stream.playableUrl,
                                                        title = request.title,
                                                        season = request.season,
                                                        episode = request.episode,
                                                        episodeTitle = request.episodeTitle,
                                                        seriesTitle = request.title,
                                                    ),
                                                )
                                            }
                                                .onSuccess { media ->
                                                    // Always surface the catalog title to the player —
                                                    // mpv would otherwise display the gnarly release filename.
                                                    onResolved(media.url, request.title, resumePosition)
                                                }
                                                .onFailure { err ->
                                                    resolveError = err.message ?: err.toString()
                                                    resolvingStreamId = null
                                                }
                                        }
                                    }
                                },
                            )
                        }
                        if (search.errors.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Providers reporting errors: " +
                                        search.errors.joinToString { it.providerName },
                                    color = CyferTextTertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (search.addonErrors.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Addons reporting errors: " +
                                        search.addonErrors.joinToString { it.name },
                                    color = CyferTextTertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredSpinner(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = CyferAccent)
            Text(label, color = CyferTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Surface(color = CyferCardSurface, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = message,
            color = CyferError,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// Provider-kind pill colours mirror the desktop's TORRENTS / ADDONS /
// DIRECT chips on the source picker, plus our local "DOWNLOADED" tag
// for already-pinned content (always rendered first).
private val ProviderKindTorrents = Color(0xFFF2912D)
private val ProviderKindAddons = CyferAccentBlue
private val ProviderKindDirect = Color(0xFF4CAF50)
private val ProviderKindDownloaded = Color(0xFF00C853)  // material green

private fun providerKind(stream: ResolvedStream): String = when {
    stream.addonId == "cyfer.downloaded" -> "DOWNLOADED"
    stream.addonId.startsWith("cyfer.direct") -> "DIRECT"
    stream.addonId.startsWith("cyfer.torrent.") -> "TORRENTS"
    else -> "ADDONS"
}

private fun providerKindColor(kind: String): Color = when (kind) {
    "TORRENTS" -> ProviderKindTorrents
    "DIRECT" -> ProviderKindDirect
    "DOWNLOADED" -> ProviderKindDownloaded
    else -> ProviderKindAddons
}

private fun sourceLabel(stream: ResolvedStream): String {
    val raw = stream.addonName.removePrefix("Cyfer ")
    return when (raw) {
        "RARBG-compatible" -> "RARBG"
        "The Pirate Bay" -> "TPB"
        "TorrentGalaxy" -> "TGX"
        "MagnetDL" -> "MAGNETDL"
        "KickassTorrents" -> "KAT"
        "Bangumi.moe" -> "BANGUMI"
        else -> raw.uppercase()
    }
}

private fun parseInfoHashFromMagnet(magnet: String): String? {
    val m = Regex("btih:([A-Fa-f0-9]{40}|[A-Z2-7]{32})").find(magnet) ?: return null
    val raw = m.groupValues[1]
    if (raw.length == 40) return raw.lowercase()
    // base32 → hex
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0; var value = 0
    val out = StringBuilder()
    for (c in raw.uppercase()) {
        val idx = alphabet.indexOf(c); if (idx < 0) continue
        value = (value shl 5) or idx; bits += 5
        if (bits >= 8) { bits -= 8; out.append("%02x".format((value shr bits) and 0xFF)); value = value and ((1 shl bits) - 1) }
    }
    return out.toString().takeIf { it.length == 40 }
}

@Composable
private fun StreamRow(
    stream: ResolvedStream,
    resolving: Boolean,
    enabled: Boolean,
    settings: AppSettings,
    /** Null = not pinned. 0.0..1.0 = active download fraction. */
    downloadProgress: Float? = null,
    downloadComplete: Boolean = false,
    onSaveOffline: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val parsedTitle = remember(stream.title) {
        cleanReleaseTitle(stream.title).ifBlank { stream.title }
    }
    val kind = providerKind(stream)
    val kindColor = providerKindColor(kind)
    val source = sourceLabel(stream)
    val tags = remember(stream) { streamDisplayTagsForRow(stream) }
    val channel = remember(stream.title) { extractChannelLayout(stream.title) }
    val resolution = stream.quality.resolution?.label
    // For TORRENTS rows, append the configured debrid services (RD/TB)
    // to the kind label so users see at a glance which resolver will
    // handle the magnet. Addon/direct rows aren't decorated — addons
    // return their own playable URL, direct rows never touch a debrid.
    val debridSuffix = remember(kind, settings) {
        if (kind != "TORRENTS") "" else listOfNotNull(
            if (settings.realDebridEnabled && settings.realDebridApiToken.isNotBlank()) "RD" else null,
            if (settings.torboxEnabled && settings.torboxApiToken.isNotBlank()) "TB" else null,
        ).joinToString("+")
    }

    var saved by remember(stream.playableUrl) { mutableStateOf(false) }

    Surface(
        color = if (resolving) CyferCardSurfaceLight else CyferCardSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clickable(enabled = enabled && !resolving, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        color = CyferAccent,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = CyferWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Title (left) + size (right). Seeders moved into the badge
                // row so they're discoverable on horizontal scroll.
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = parsedTitle,
                        color = CyferWhite,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    stream.size?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = it,
                            color = CyferWhite,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Badge row: scrollable badges (left) + seeders pinned right.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        // Source identity — one quiet pill: a colour-coded
                        // dot (orange torrents / blue addons / green
                        // direct·downloaded) + provider name + optional
                        // debrid suffix. Replaces the old solid-colour
                        // TORRENTS/ADDONS block + separate name pill —
                        // same information, Apple TV-quiet.
                        SourceIdentityPill(
                            dotColor = kindColor,
                            label = if (source.isNotEmpty() && source != kind) source else kind,
                            suffix = debridSuffix,
                        )
                        resolution?.let {
                            CyferTagPill(it, background = CyferBadgeBackground, foreground = CyferWhite)
                        }
                        for (tag in tags) {
                            TechLogoBadge(tag = tag, heightDp = 14)
                        }
                        for (lang in stream.quality.languages) {
                            CyferOutlinePill(lang)
                        }
                        channel?.let { CyferOutlinePill(it) }
                    }
                    stream.seeders?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "↑ $it",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ── Save-offline button — torrent rows only ───────────────
            if (onSaveOffline != null) {
                Spacer(Modifier.width(8.dp))
                DownloadActionButton(
                    saved = saved,
                    downloadProgress = downloadProgress,
                    downloadComplete = downloadComplete,
                    onSave = {
                        if (!saved) {
                            saved = true
                            onSaveOffline()
                        }
                    },
                )
            }
        }
    }
}

/**
 * App Store–style download affordance. Three states visually distinct
 * so the user always knows what's happening:
 *
 *  • **Idle** — small `Download` glyph on a dark glass background. Tap
 *    pins the torrent.
 *  • **In flight** — circular progress ring (`CircularProgressIndicator`
 *    with `progress = fraction`) wrapped around a tiny inner pause
 *    glyph. Mimics the iOS App Store download ring.
 *  • **Complete** — green filled circle with a check.
 */
@Composable
private fun DownloadActionButton(
    saved: Boolean,
    downloadProgress: Float?,
    downloadComplete: Boolean,
    onSave: () -> Unit,
) {
    val showProgress = !downloadComplete && (downloadProgress != null || saved)
    val showComplete = downloadComplete
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showComplete -> {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF00C853),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Downloaded",
                            tint = CyferBlack,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            showProgress -> {
                // Track ring (faint full circle) + active progress arc.
                CircularProgressIndicator(
                    progress = { 1f },
                    color = Color.White.copy(alpha = 0.18f),
                    strokeWidth = 2.5.dp,
                    trackColor = Color.Transparent,
                    modifier = Modifier.size(34.dp),
                )
                CircularProgressIndicator(
                    progress = { downloadProgress ?: 0f },
                    color = CyferAccent,
                    strokeWidth = 2.5.dp,
                    trackColor = Color.Transparent,
                    modifier = Modifier.size(34.dp),
                )
                // Inner stop-square — matches App Store's "tap to cancel"
                // affordance. Tap-target lives on the outer Box so the
                // ring is hittable.
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(CyferWhite),
                )
            }
            else -> {
                FilledIconButton(
                    onClick = onSave,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferDarkSurface,
                        contentColor = CyferWhite,
                    ),
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Save offline",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Compound pill: provider kind on the left + (optional) attached RD/TB
 * tag on the right. Visually they read as one piece so users can tell
 * at a glance which resolver will handle the magnet.
 *
 * Without any configured debrid this collapses back to a plain solid
 * pill — no visual noise about "P2P" since that's the default.
 */
@Composable
private fun SourceIdentityPill(
    dotColor: Color,
    label: String,
    suffix: String,
) {
    Surface(color = CyferCardSurfaceLight, shape = RoundedCornerShape(3.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = label,
                color = CyferWhite,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PendingProvidersPill(count: Int) {
    Surface(
        color = CyferCardSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                color = CyferAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Still searching $count provider${if (count == 1) "" else "s"}…",
                color = CyferTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Filter chrome (mirrors the desktop source picker tabs + chips)
// ═══════════════════════════════════════════════════════════════

private enum class ProviderFilter(val label: String) {
    All("All"), Torrents("Torrents"), Addons("Addons"), Direct("Direct"),
}

private enum class ResolutionFilter(val label: String) {
    All("ALL"), K4("4K"), P1080("1080P"), P720("720P"),
}
