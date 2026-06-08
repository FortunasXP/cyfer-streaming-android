package app.cyfer.streaming.android.ui.anime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.kitsu.AnimeEpisode
import app.cyfer.streaming.android.data.kitsu.AnimeTitle
import app.cyfer.streaming.android.data.kitsu.KitsuRepository
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.data.library.WatchlistEntry
import app.cyfer.streaming.android.data.torrent.TorrentMediaType
import app.cyfer.streaming.android.ui.common.CyferTagPill
import app.cyfer.streaming.android.ui.sources.SourcePickerRequest
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun AnimeDetailScreen(
    item: AnimeTitle,
    onBack: () -> Unit,
    onRequestSources: (SourcePickerRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val libraryRepo = remember { LibraryRepository.get(ctx) }
    val watchlist by libraryRepo.watchlist.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    var hydrated by remember(item.kitsuId) { mutableStateOf(item) }
    var episodes by remember(item.kitsuId) { mutableStateOf<List<AnimeEpisode>>(emptyList()) }
    var episodesLoading by remember(item.kitsuId) { mutableStateOf(item.animeKind == "series") }

    LaunchedEffect(item.kitsuId) {
        runCatching { KitsuRepository.getAnime(item.kitsuId) }
            .onSuccess { it?.let { fresh -> hydrated = fresh } }
    }
    LaunchedEffect(item.kitsuId) {
        if (item.animeKind != "series") return@LaunchedEffect
        episodes = runCatching { KitsuRepository.getEpisodes(item.kitsuId) }
            .getOrElse { emptyList() }
        episodesLoading = false
    }

    val inWatchlist = remember(watchlist, hydrated) {
        watchlist.any { it.tmdbId == hydrated.id && it.mediaType == "anime" }
    }

    fun playRequest(seasonNumber: Int? = null, episodeNumber: Int? = null) {
        // We always send the romaji ("Sousou no Frieren") alongside the
        // English ("Frieren: Beyond Journey's End") and the Japanese
        // ("葬送のフリーレン") so every tracker has a chance of matching
        // its preferred index format. The torrent search dedupes these
        // internally so duplicate canonicals don't waste round-trips.
        val all = hydrated.allSearchableTitles
        val (primary, alternates) = if (all.isEmpty()) hydrated.title to emptyList()
            else all.first() to all.drop(1)
        onRequestSources(
            SourcePickerRequest(
                title = primary,
                year = hydrated.year,
                mediaType = TorrentMediaType.anime,
                backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl,
                posterUrl = hydrated.posterUrl,
                season = seasonNumber,
                episode = episodeNumber,
                alternateTitles = alternates,
            ),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack),
    ) {
        item {
            AnimeDetailHero(
                item = hydrated,
                inWatchlist = inWatchlist,
                onBack = onBack,
                onPlay = {
                    if (hydrated.animeKind == "movie" || episodes.isEmpty()) {
                        playRequest()
                    } else {
                        val first = episodes.firstOrNull { it.seasonNumber == 1 }
                            ?: episodes.first()
                        playRequest(first.seasonNumber, first.episodeNumber)
                    }
                },
                onToggleWatchlist = {
                    scope.launch {
                        libraryRepo.toggleWatchlist(
                            WatchlistEntry(
                                tmdbId = hydrated.id,
                                mediaType = "anime",
                                title = hydrated.title,
                                posterUrl = hydrated.posterUrl,
                                backdropUrl = hydrated.backdropUrl,
                                year = hydrated.year,
                                voteAverage = hydrated.voteAverage ?: 0f,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
        }

        if (hydrated.overview.isNotBlank()) {
            item {
                Text(
                    text = hydrated.overview,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        if (hydrated.genres.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    hydrated.genres.take(8).forEach { g ->
                        CyferTagPill(text = g.uppercase(), background = CyferCardSurfaceLight, foreground = CyferTextSecondary)
                    }
                }
            }
        }

        if (hydrated.animeKind == "series") {
            item {
                Text(
                    text = "EPISODES",
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            if (episodesLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = CyferAccent) }
                }
            } else if (episodes.isEmpty()) {
                item {
                    Text(
                        text = "No episode list yet on Kitsu — tap Play to pick a source manually.",
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(episodes.size, key = { i -> "ep-${episodes[i].id}-${episodes[i].seasonNumber}-${episodes[i].episodeNumber}" }) { idx ->
                    val ep = episodes[idx]
                    AnimeEpisodeRow(
                        ep = ep,
                        onClick = { playRequest(ep.seasonNumber, ep.episodeNumber) },
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(120.dp)) }
    }
}

@Composable
private fun AnimeDetailHero(
    item: AnimeTitle,
    inWatchlist: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(560.dp)) {
        val bg = item.backdropUrl ?: item.posterUrl
        if (!bg.isNullOrBlank()) {
            AsyncImage(
                model = bg,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent,
                            CyferBlack.copy(alpha = 0.7f),
                            CyferBlack,
                        ),
                    ),
                ),
        )

        // Back button
        FilledIconButton(
            onClick = onBack,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.45f),
                contentColor = CyferWhite,
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 6.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "ANIME",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = item.title,
                color = CyferWhite,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item.voteAverage?.let { rating ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = CyferGold, modifier = Modifier.size(14.dp))
                        Text("%.1f".format(rating), color = CyferWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
                item.year?.let { Text(it, color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                item.episodeCount?.let { Text("$it eps", color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                if (item.animeKind == "movie") Text("Movie", color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                item.status?.let { Text(it, color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(onClick = onPlay, shape = RoundedCornerShape(28.dp), color = CyferAccent) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = CyferBlack)
                        Text(
                            text = if (item.animeKind == "movie") "Play" else "Play S1 · EP1",
                            color = CyferBlack,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Surface(onClick = onToggleWatchlist, shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.12f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (inWatchlist) Icons.Filled.Check else Icons.Filled.Add,
                            contentDescription = null,
                            tint = CyferWhite,
                        )
                        Text(
                            text = if (inWatchlist) "In list" else "My List",
                            color = CyferWhite,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeEpisodeRow(ep: AnimeEpisode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 70.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CyferCardSurface),
        ) {
            if (!ep.stillUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ep.stillUrl,
                    contentDescription = ep.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Tv, contentDescription = null, tint = CyferTextTertiary, modifier = Modifier.size(20.dp))
                }
            }
            // Episode number badge bottom-left
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "EP ${ep.episodeNumber.toString().padStart(2, '0')}",
                    color = CyferWhite,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ep.name,
                color = CyferWhite,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                ep.airDate?.takeIf { it.isNotBlank() },
                ep.runtime?.let { "${it}m" },
            ).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (ep.overview.isNotBlank()) {
                Text(
                    text = ep.overview,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
