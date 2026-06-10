package app.cyfer.streaming.android.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cyfer.streaming.android.data.tmdb.TmdbDetailResponse
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import app.cyfer.streaming.android.data.torrent.StreamResolution
import app.cyfer.streaming.android.data.torrent.TechTag
import app.cyfer.streaming.android.data.torrent.TorrentMediaType
import app.cyfer.streaming.android.data.torrent.aggregateTechTags
import app.cyfer.streaming.android.ui.common.TechLogoBadge
import app.cyfer.streaming.android.ui.common.TitleLogo
import app.cyfer.streaming.android.ui.home.PosterCard
import app.cyfer.streaming.android.ui.sources.SourcePickerRequest
import app.cyfer.streaming.android.ui.sources.rememberSourceSearch
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage

@Composable
fun TitleDetailsScreen(
    tmdbId: Int,
    mediaType: String, // "movie" or "tv"
    onBack: () -> Unit,
    onRequestSources: (SourcePickerRequest) -> Unit,
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    onPersonClick: (personId: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val scrollState = rememberScrollState()
    var details by remember { mutableStateOf<TmdbDetailResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(tmdbId, mediaType) {
        isLoading = true
        try {
            details = if (mediaType == "tv") {
                TmdbRepository.getTvDetails(tmdbId)
            } else {
                TmdbRepository.getMovieDetails(tmdbId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize().background(CyferBlack), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CyferAccent)
        }
        return
    }

    val item = details ?: return

    // Build the source-search request as soon as Details has loaded —
    // shared with the SourcePickerSheet via a module-level TTL cache so
    // tapping Play opens the picker with results already in hand.
    val pickerRequest = remember(item.id, mediaType) {
        SourcePickerRequest(
            title = item.displayTitle,
            year = item.displayYear.takeIf { it.isNotEmpty() },
            mediaType = if (mediaType == "tv") TorrentMediaType.tv else TorrentMediaType.movie,
            imdbId = item.stremioId,
            backdropUrl = item.backdropUrl ?: item.posterUrl,
            tmdbId = item.id,
            posterUrl = item.posterUrl,
        )
    }

    // Library state for the "Add to List" toggle + Resume affordance.
    val context = LocalContext.current
    val libraryRepo = remember { app.cyfer.streaming.android.data.library.LibraryRepository.get(context) }
    val watchlist by libraryRepo.watchlist.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by libraryRepo.progress.collectAsStateWithLifecycle(initialValue = emptyList())
    // Downloads tied to this title — surfaces an active progress
    // indicator next to the Play button so the user knows it's working.
    val downloadsRepo = remember { app.cyfer.streaming.android.data.downloads.DownloadsRepository.get(context) }
    val downloadEntries by downloadsRepo.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val titleDownloads = remember(downloadEntries, item.id) {
        downloadEntries.filter { it.tmdbId == item.id }
    }
    val inWatchlist = watchlist.any { it.tmdbId == item.id && it.mediaType == mediaType }
    // Most recent unwatched entry for this title (movie or any of its episodes).
    val resumeEntry = remember(progress, item.id, mediaType) {
        progress
            .filter { it.tmdbId == item.id && it.mediaType == mediaType && !it.watched && it.position > 0.0 }
            .maxByOrNull { it.updatedAt }
    }
    // Movie-level watched flag — drives the "Mark watched" toggle. For
    // series the watched state is tracked per-episode instead.
    val titleWatched = remember(progress, item.id, mediaType) {
        progress.any {
            it.tmdbId == item.id && it.mediaType == mediaType &&
                it.season == null && it.episode == null && it.watched
        }
    }
    val libraryScope = rememberCoroutineScope()
    // Trakt push on manual marks — fire-and-forget when connected.
    val traktRepo = remember { app.cyfer.streaming.android.data.trakt.TraktRepository.get(context) }
    val sourceSearch = rememberSourceSearch(pickerRequest)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .verticalScroll(scrollState)
    ) {
        // ── Hero — mirrors the desktop `ctv-hero` layout ────────────
        val isMovie = mediaType != "tv"
        val isEpisodic = mediaType == "tv"
        val mediaLabel = if (mediaType == "tv") "Series" else "Movie"

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp),
        ) {
            AsyncImage(
                model = item.backdropUrl ?: item.posterUrl,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Soft top → bottom scrim so the eyebrow/logo/overview are legible
            // over any backdrop, fading to solid black at the body fold.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.55f),
                                0.18f to Color.Transparent,
                                0.55f to CyferBlack.copy(alpha = 0.55f),
                                1.0f to CyferBlack,
                            ),
                        ),
                    ),
            )

            // Top bar: Back (left) + Download status pill (center) +
            // Watchlist (right). Pill is absolute-centered so a long
            // "Downloading 47%" label doesn't shift the icons.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            ) {
                FilledIconButton(
                    onClick = onBack,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferCardSurface.copy(alpha = 0.6f),
                        contentColor = CyferWhite,
                    ),
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                if (titleDownloads.isNotEmpty()) {
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        DownloadStatusBar(entries = titleDownloads)
                    }
                }
                FilledIconButton(
                    onClick = {
                        libraryScope.launch {
                            val added = libraryRepo.toggleWatchlist(
                                app.cyfer.streaming.android.data.library.WatchlistEntry(
                                    tmdbId = item.id,
                                    mediaType = mediaType,
                                    title = item.displayTitle,
                                    posterUrl = item.posterUrl,
                                    backdropUrl = item.backdropUrl,
                                    year = item.displayYear.takeIf { it.isNotEmpty() },
                                    voteAverage = item.vote_average,
                                    addedAt = System.currentTimeMillis(),
                                ),
                            )
                            // Mirror the toggle to Trakt's watchlist —
                            // fire-and-forget, keyed by IMDb id.
                            traktRepo.pushWatchlistAsync(
                                add = added,
                                imdbId = item.stremioId,
                                mediaType = mediaType,
                            )
                        }
                    },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (inWatchlist) CyferAccent else CyferCardSurface.copy(alpha = 0.6f),
                        contentColor = if (inWatchlist) CyferBlack else CyferWhite,
                    ),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = if (inWatchlist) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = if (inWatchlist) "Remove from List" else "Add to List",
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                // Eyebrow: "Year · Movie · Genre1 · Genre2" — year
                // anchors the left edge so the user instantly clocks
                // when the title is from.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val eyebrowItems = buildList {
                        if (item.displayYear.isNotEmpty()) add(item.displayYear)
                        add(mediaLabel)
                        addAll(item.genres.take(if (isMovie) 2 else 3).map { it.name })
                    }
                    eyebrowItems.forEachIndexed { idx, t ->
                        if (idx > 0) HeroDot()
                        EyebrowText(t)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TitleLogo(
                    title = item.displayTitle,
                    logoUrl = item.logoUrl,
                    maxHeightDp = 130,
                    fallbackStyle = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = CyferWhite,
                    ),
                )

                // Tagline only — the old "Year · Runtime" fallback just
                // repeated the eyebrow (year) and stats row (runtime).
                if (!item.tagline.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = item.tagline,
                        color = CyferWhite,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Stats row — runtime · ★ rating, then tech badges
                // (resolution + DV/Atmos/IMAX) inline on the SAME line.
                // Year lives in the eyebrow above so it stays the first
                // thing the eye lands on.
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val metaItems = buildList {
                        if (item.runtimeFormatted.isNotEmpty()) add(item.runtimeFormatted)
                        if (item.vote_average > 0f) add("★ ${item.ratingFormatted}")
                    }
                    metaItems.forEachIndexed { idx, t ->
                        if (idx > 0) HeroDot()
                        Text(
                            text = t,
                            color = CyferWhite,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (metaItems.isNotEmpty()) Spacer(modifier = Modifier.width(2.dp))

                    // Resolution badge only once a real source reported
                    // one — no fabricated "4K" placeholder while loading.
                    sourceSearch.streams
                        .firstNotNullOfOrNull { it.quality.resolution }
                        ?.label
                        ?.let { SolidBadge(it) }
                    val tags = aggregateTechTags(sourceSearch.streams)
                    tags.videoLogos.forEach { TechLogoBadge(it) }
                    tags.audioLogos.forEach { TechLogoBadge(it) }
                    if (sourceSearch.loading) {
                        CircularProgressIndicator(
                            color = CyferTextTertiary,
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────
        //  Body — every concern in its own section, consistent
        //  28 dp gutters between, 20 dp horizontal padding.
        // ─────────────────────────────────────────────────────────

        Spacer(modifier = Modifier.height(18.dp))

        // ── Section: Play CTA ────────────────────────────────────
        // Compact pill — fixed width so it doesn't span the whole
        // screen. Add-to-Watchlist lives in the hero top bar; this row
        // is just the primary playback action plus, when relevant, the
        // App Store-style download status pill.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        onRequestSources(
                            SourcePickerRequest(
                                title = item.displayTitle,
                                year = item.displayYear.takeIf { it.isNotEmpty() },
                                mediaType = if (mediaType == "tv") TorrentMediaType.tv else TorrentMediaType.movie,
                                season = resumeEntry?.season,
                                episode = resumeEntry?.episode,
                                imdbId = item.stremioId,
                                backdropUrl = item.backdropUrl ?: item.posterUrl,
                                tmdbId = item.id,
                                posterUrl = item.posterUrl,
                            ),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyferWhite,
                        contentColor = CyferBlack,
                    ),
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 0.dp),
                    modifier = Modifier.height(42.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buildPlayLabel(resumeEntry, isEpisodic),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Mark watched / unwatched — movies only. (Series track
                // watched state per-episode in the Episodes section.)
                if (isMovie) {
                    val runtimeSec = (item.runtime ?: 0) * 60.0
                    MarkWatchedButton(
                        watched = titleWatched,
                        onToggle = {
                            libraryScope.launch {
                                if (titleWatched) {
                                    libraryRepo.markUnwatched(item.id, mediaType)
                                    item.stremioId?.let {
                                        traktRepo.removeWatched(it, mediaType)
                                    }
                                } else {
                                    libraryRepo.markWatched(
                                        tmdbId = item.id,
                                        mediaType = mediaType,
                                        title = item.displayTitle,
                                        posterUrl = item.posterUrl,
                                        backdropUrl = item.backdropUrl,
                                        knownDurationSeconds = runtimeSec.takeIf { it > 0 },
                                    )
                                    item.stremioId?.let {
                                        traktRepo.markWatched(it, mediaType)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ── Section: Synopsis ────────────────────────────────────
        if (!item.overview.isNullOrBlank()) {
            SectionLabel("Synopsis")
            Spacer(modifier = Modifier.height(8.dp))
            var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
            Text(
                text = item.overview,
                color = CyferTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clickable { expanded = !expanded },
            )
            Spacer(modifier = Modifier.height(28.dp))
        }

        // ── Section: Genres ─────────────────────────────────────
        if (item.genres.isNotEmpty()) {
            SectionLabel("Genres")
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.genres.forEach { g ->
                    app.cyfer.streaming.android.ui.common.CyferTagPill(
                        text = g.name.uppercase(),
                        background = CyferCardSurfaceLight,
                        foreground = CyferTextSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        // ── Section: Cast & Crew ─────────────────────────────────
        val cast = item.credits?.cast
        if (!cast.isNullOrEmpty()) {
            SectionLabel("Cast & Crew")
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(cast.take(15)) { person ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(80.dp)
                            .clickable { onPersonClick(person.id) }
                    ) {
                        AsyncImage(
                            model = person.profileUrl,
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(CyferCardSurface)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = person.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyferWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!person.character.isNullOrBlank()) {
                            Text(
                                text = person.character,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyferTextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        // ── Episodes (TV only) ──────────────────────────────────────
        val seasons = item.seasons
        if (mediaType == "tv" && !seasons.isNullOrEmpty()) {
            val realSeasons = remember(seasons) { seasons.filter { it.season_number > 0 } }
            val defaultSeason = remember(item.id, resumeEntry, realSeasons) {
                resumeEntry?.season?.takeIf { s -> realSeasons.any { it.season_number == s } }
                    ?: realSeasons.firstOrNull()?.season_number
                    ?: 1
            }
            var selectedSeason by rememberSaveable(item.id) { mutableIntStateOf(defaultSeason) }
            var episodes by remember(item.id, selectedSeason) {
                mutableStateOf<List<app.cyfer.streaming.android.data.tmdb.TmdbEpisode>>(emptyList())
            }
            var loadingEpisodes by remember(item.id, selectedSeason) { mutableStateOf(false) }

            LaunchedEffect(item.id, selectedSeason) {
                loadingEpisodes = true
                try {
                    episodes = TmdbRepository.getTvSeason(item.id, selectedSeason)
                } catch (e: Exception) {
                    e.printStackTrace()
                    episodes = emptyList()
                } finally {
                    loadingEpisodes = false
                }
            }

            // Episodes header with a "Mark season watched" action on the
            // right. Computes the season-watched state from the loaded
            // episode list + saved progress.
            val seasonAllWatched = remember(progress, item.id, selectedSeason, episodes) {
                episodes.isNotEmpty() && episodes.all { ep ->
                    progress.any {
                        it.tmdbId == item.id && it.mediaType == "tv" &&
                            it.season == ep.season_number && it.episode == ep.episode_number && it.watched
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Episodes", modifier = Modifier.weight(1f))
                if (episodes.isNotEmpty()) {
                    Surface(
                        onClick = {
                            libraryScope.launch {
                                if (seasonAllWatched) {
                                    val keys = episodes.map { ep ->
                                        app.cyfer.streaming.android.data.library.ProgressEntry(
                                            tmdbId = item.id, mediaType = "tv", title = "",
                                            season = ep.season_number, episode = ep.episode_number,
                                            position = 0.0, duration = 0.0, updatedAt = 0,
                                        ).key
                                    }
                                    libraryRepo.markEpisodesUnwatched(keys)
                                    item.stremioId?.let { imdb ->
                                        episodes.forEach { ep ->
                                            traktRepo.removeWatched(imdb, "tv", ep.season_number, ep.episode_number)
                                        }
                                    }
                                } else {
                                    val entries = episodes.map { ep ->
                                        val dur = ((ep.runtime ?: item.episode_run_time?.firstOrNull() ?: 1) * 60.0).coerceAtLeast(1.0)
                                        app.cyfer.streaming.android.data.library.ProgressEntry(
                                            tmdbId = item.id, mediaType = "tv",
                                            title = "${item.displayTitle} — S${ep.season_number}E${ep.episode_number}",
                                            posterUrl = ep.stillUrl ?: item.posterUrl,
                                            backdropUrl = item.backdropUrl,
                                            season = ep.season_number, episode = ep.episode_number,
                                            seriesTmdbId = item.id, seriesTitle = item.displayTitle,
                                            position = dur, duration = dur, updatedAt = System.currentTimeMillis(),
                                        )
                                    }
                                    libraryRepo.markEpisodesWatched(entries)
                                    item.stremioId?.let { imdb ->
                                        traktRepo.markSeasonWatched(
                                            imdb, selectedSeason, episodes.map { it.episode_number },
                                        )
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (seasonAllWatched) CyferAccent.copy(alpha = 0.18f) else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (seasonAllWatched) Icons.Default.Replay else Icons.Default.Check,
                                contentDescription = null,
                                tint = CyferAccent,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = if (seasonAllWatched) "Unwatch season" else "Mark season",
                                color = CyferAccent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Season pill row — scrollable so 20+ season shows still fit.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                realSeasons.forEach { season ->
                    SeasonPill(
                        label = season.name?.takeIf { it.startsWith("Season", ignoreCase = true) }
                            ?: "Season ${season.season_number}",
                        selected = season.season_number == selectedSeason,
                        onClick = { selectedSeason = season.season_number },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Episode list
            if (loadingEpisodes && episodes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = CyferAccent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    episodes.forEach { ep ->
                        val epProgress = remember(progress, item.id, ep.season_number, ep.episode_number) {
                            progress.firstOrNull {
                                it.tmdbId == item.id &&
                                    it.mediaType == "tv" &&
                                    it.season == ep.season_number &&
                                    it.episode == ep.episode_number
                            }
                        }
                        EpisodeRow(
                            episode = ep,
                            progress = epProgress,
                            onPlay = {
                                onRequestSources(
                                    SourcePickerRequest(
                                        title = item.displayTitle,
                                        year = item.displayYear.takeIf { it.isNotEmpty() },
                                        mediaType = TorrentMediaType.tv,
                                        season = ep.season_number,
                                        episode = ep.episode_number,
                                        episodeTitle = ep.name,
                                        imdbId = item.stremioId,
                                        backdropUrl = item.backdropUrl ?: item.posterUrl,
                                        tmdbId = item.id,
                                        posterUrl = item.posterUrl,
                                    ),
                                )
                            },
                            onToggleWatched = {
                                libraryScope.launch {
                                    if (epProgress?.watched == true) {
                                        libraryRepo.markUnwatched(item.id, "tv", ep.season_number, ep.episode_number)
                                        item.stremioId?.let {
                                            traktRepo.removeWatched(it, "tv", ep.season_number, ep.episode_number)
                                        }
                                    } else {
                                        val dur = ((ep.runtime ?: item.episode_run_time?.firstOrNull() ?: 1) * 60.0).coerceAtLeast(1.0)
                                        libraryRepo.markWatched(
                                            tmdbId = item.id,
                                            mediaType = "tv",
                                            title = "${item.displayTitle} — S${ep.season_number}E${ep.episode_number}",
                                            posterUrl = ep.stillUrl ?: item.posterUrl,
                                            backdropUrl = item.backdropUrl,
                                            season = ep.season_number,
                                            episode = ep.episode_number,
                                            seriesTmdbId = item.id,
                                            seriesTitle = item.displayTitle,
                                            knownDurationSeconds = dur,
                                        )
                                        item.stremioId?.let {
                                            traktRepo.markWatched(it, "tv", ep.season_number, ep.episode_number)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        // Recommendations — TMDb's ML-scored "What to watch next" list,
        // separate from `similar` (which uses simple metadata overlap).
        var recommendations by remember(item.id, mediaType) {
            mutableStateOf<List<app.cyfer.streaming.android.data.tmdb.TmdbItem>>(emptyList())
        }
        LaunchedEffect(item.id, mediaType) {
            recommendations = runCatching {
                if (mediaType == "tv")
                    app.cyfer.streaming.android.data.tmdb.TmdbRepository.getTvRecommendations(item.id)
                else
                    app.cyfer.streaming.android.data.tmdb.TmdbRepository.getMovieRecommendations(item.id)
            }.getOrElse { emptyList() }
        }
        if (recommendations.isNotEmpty()) {
            SectionLabel("Recommended")
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recommendations) { rec ->
                    PosterCard(
                        item = rec,
                        onClick = { onTitleClick(rec.id, rec.media_type ?: mediaType) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        // Similar — TMDb's metadata-overlap list.
        val similar = item.similar?.results
        if (!similar.isNullOrEmpty()) {
            SectionLabel("More Like This")
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(similar) { simItem ->
                    PosterCard(
                        item = simItem,
                        onClick = { onTitleClick(simItem.id, mediaType) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

/**
 * Watched toggle — a circular glass icon button matching the hero's
 * watchlist button (Apple TV keeps secondary actions as quiet icon
 * circles beside the white Play pill). Accent-filled when watched.
 */
@Composable
private fun MarkWatchedButton(watched: Boolean, onToggle: () -> Unit) {
    FilledIconButton(
        onClick = onToggle,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (watched) CyferAccent else CyferCardSurface,
            contentColor = if (watched) CyferBlack else CyferWhite,
        ),
        modifier = Modifier.size(42.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = if (watched) "Watched — tap to unwatch" else "Mark watched",
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun EyebrowText(text: String) {
    Text(
        text = text,
        color = CyferTextSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Uppercase section eyebrow used to divide the Details body into
 * Synopsis / Genres / Episodes / Cast / Recommended / Similar. Matches
 * the desktop Info-page section headers (small caps, accent letter
 * spacing, 20 dp gutter).
 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = CyferTextSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun HeroDot() {
    Text(text = "·", color = CyferTextTertiary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SolidBadge(text: String) {
    Surface(color = CyferCardSurface, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = text,
            color = CyferWhite,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun OutlineBadge(text: String) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyferTextTertiary),
    ) {
        Text(
            text = text,
            color = CyferTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}


/**
 * "Play" / "Resume" / "Resume S1 E2" depending on whether we have saved
 * progress for the current title. Mirrors the desktop heroPrimaryLabel.
 */
private fun buildPlayLabel(
    entry: app.cyfer.streaming.android.data.library.ProgressEntry?,
    isEpisodic: Boolean,
): String {
    if (entry == null) {
        return if (isEpisodic) "Play S1 E1" else "Play"
    }
    if (isEpisodic && entry.season != null && entry.episode != null) {
        return "Resume S${entry.season} E${entry.episode}"
    }
    val remaining = (entry.duration - entry.position).coerceAtLeast(0.0).toInt()
    val h = remaining / 3600
    val m = (remaining % 3600) / 60
    val label = when {
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
    return "Resume · $label left"
}

@Composable
private fun SeasonPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) CyferAccent else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, CyferCardSurfaceLight),
    ) {
        Text(
            text = label,
            color = if (selected) CyferBlack else CyferWhite,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: app.cyfer.streaming.android.data.tmdb.TmdbEpisode,
    progress: app.cyfer.streaming.android.data.library.ProgressEntry?,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit = {},
) {
    val isWatched = progress?.watched == true
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(12.dp),
        color = CyferDarkSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyferCardSurface),
                ) {
                    if (!episode.stillUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = episode.stillUrl,
                            contentDescription = episode.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Watched stills get a dimmer scrim + corner check so
                    // the user can scan a season at a glance.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = if (isWatched) 0.6f else 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isWatched) Icons.Filled.Check else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = CyferWhite,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    // Progress bar at the bottom of the still
                    if (progress != null && progress.duration > 0 && !progress.watched) {
                        val frac = (progress.position / progress.duration).coerceIn(0.0, 1.0).toFloat()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.BottomCenter)
                                .background(Color.White.copy(alpha = 0.2f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(frac)
                                    .fillMaxHeight()
                                    .background(CyferWhite),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "E${episode.episode_number}",
                            color = CyferTextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = episode.displayTitle,
                            color = if (isWatched) CyferTextSecondary else CyferWhite,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Per-episode watched toggle. Stops the row's
                        // onClick (play) from firing via its own surface.
                        Surface(
                            onClick = onToggleWatched,
                            shape = CircleShape,
                            color = if (isWatched) CyferAccent else CyferCardSurface,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = if (isWatched) "Mark unwatched" else "Mark watched",
                                    tint = if (isWatched) CyferBlack else CyferTextTertiary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    val metaParts = buildList {
                        episode.runtimeFormatted.takeIf { it.isNotEmpty() }?.let { add(it) }
                        episode.air_date?.takeIf { it.length >= 4 }?.let { add(it.substring(0, 4)) }
                        if (progress?.watched == true) add("Watched")
                    }
                    if (metaParts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = metaParts.joinToString("  ·  "),
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (!episode.overview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = episode.overview,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Compact App Store–style status row shown beneath the Play button when
 * the user has saved one or more sources for this title offline. One
 * row per active download: spinner ring around a download glyph + a
 * tiny status line. Completed entries are filtered out elsewhere.
 */
@Composable
private fun DownloadStatusBar(
    entries: List<app.cyfer.streaming.android.data.downloads.DownloadEntry>,
) {
    val active = entries.filter {
        it.status != app.cyfer.streaming.android.data.downloads.DownloadStatus.Completed
    }
    // Checking pass: libtorrent's verifying pieces it already has on disk.
    // The bytes ARE there — we just haven't proven them to libtorrent yet,
    // so show a neutral pill instead of misleading "Downloading XX%".
    val checking = active.firstOrNull {
        it.status == app.cyfer.streaming.android.data.downloads.DownloadStatus.Checking
    }
    if (checking != null) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = androidx.compose.ui.graphics.Color(0xFFFFA000), // amber 700
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    color = CyferBlack,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Checking…",
                    color = CyferBlack,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        return
    }
    if (active.isEmpty()) {
        // All complete — show a single ready badge.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = androidx.compose.ui.graphics.Color(0xFF00C853),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                    contentDescription = null,
                    tint = CyferBlack,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Available offline",
                    color = CyferBlack,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        return
    }
    val entry = active.first()
    val pct = if (entry.sizeBytes > 0)
        (entry.downloadedBytes.toFloat() / entry.sizeBytes).coerceIn(0f, 1f) else 0f
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CyferCardSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { 1f },
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f),
                    strokeWidth = 2.dp,
                    trackColor = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.size(20.dp),
                )
                CircularProgressIndicator(
                    progress = { pct },
                    color = CyferAccent,
                    strokeWidth = 2.dp,
                    trackColor = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = when (entry.status) {
                    app.cyfer.streaming.android.data.downloads.DownloadStatus.Downloading -> "Downloading · ${(pct * 100).toInt()}%"
                    app.cyfer.streaming.android.data.downloads.DownloadStatus.Paused -> "Paused · ${(pct * 100).toInt()}%"
                    app.cyfer.streaming.android.data.downloads.DownloadStatus.Checking -> "Checking…"
                    app.cyfer.streaming.android.data.downloads.DownloadStatus.Error -> "Error"
                    app.cyfer.streaming.android.data.downloads.DownloadStatus.Queued -> "Queued"
                    else -> "Ready"
                },
                color = CyferWhite,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
