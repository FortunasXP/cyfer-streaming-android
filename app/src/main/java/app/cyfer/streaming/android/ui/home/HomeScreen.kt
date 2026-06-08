package app.cyfer.streaming.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MovieFilter
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.data.library.ProgressEntry
import app.cyfer.streaming.android.data.tmdb.TmdbItem
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import app.cyfer.streaming.android.data.torrent.TorrentMediaType
import app.cyfer.streaming.android.ui.sources.SourcePickerRequest
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage

@Composable
fun HomeScreen(
    onRequestSources: (SourcePickerRequest) -> Unit,
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    onOpenCatalog: (app.cyfer.streaming.android.ui.catalog.CatalogSource) -> Unit = {},
    onOpenAnime: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenContinueAll: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    var featured by remember { mutableStateOf<TmdbItem?>(null) }
    var featuredLogoUrl by remember { mutableStateOf<String?>(null) }
    var trendingMovies by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var nowPlaying by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var popularMovies by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var popularShows by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            val trending = TmdbRepository.getTrendingMovies()
            featured = trending.firstOrNull()
            trendingMovies = trending.drop(1)

            nowPlaying = TmdbRepository.getNowPlaying()
            popularMovies = TmdbRepository.getPopularMovies()
            popularShows = TmdbRepository.getPopularShows()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Resolve the TMDb logo for the featured hero in a follow-up call —
    // logos aren't included in the trending list response.
    LaunchedEffect(featured?.id) {
        val current = featured ?: return@LaunchedEffect
        featuredLogoUrl = TmdbRepository.getBestLogoUrl(
            id = current.id,
            mediaType = if (current.media_type == "tv") "tv" else "movie",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .verticalScroll(scrollState)
    ) {
        // ── Hero + overlaid top bar ─────────────────────────────
        Box(modifier = Modifier.fillMaxWidth()) {
            if (featured != null) {
                val item = featured!!
                HeroCard(
                    item = item,
                    logoUrl = featuredLogoUrl,
                    onPlay = {
                        onRequestSources(
                            SourcePickerRequest(
                                title = item.displayTitle,
                                year = item.displayYear.takeIf { it.isNotEmpty() },
                                mediaType = if (item.media_type == "tv") TorrentMediaType.tv else TorrentMediaType.movie,
                                backdropUrl = item.backdropUrl ?: item.posterUrl,
                            ),
                        )
                    },
                    onAdd = { }
                )
            } else {
                // Loading placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                        .background(CyferDarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyferAccent)
                }
            }

        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Quick-access strip — the three "secondary" screens that
        //     used to live in the bottom nav. Each is a glass pill with
        //     a themed icon + label, scrolls horizontally on narrow
        //     widths to stay aligned with the row gutters above/below.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickAccessPill(
                label = "Anime",
                icon = Icons.Filled.MovieFilter,
                onClick = onOpenAnime,
                modifier = Modifier.weight(1f),
            )
            QuickAccessPill(
                label = "Calendar",
                icon = Icons.Filled.CalendarMonth,
                onClick = onOpenCalendar,
                modifier = Modifier.weight(1f),
            )
            QuickAccessPill(
                label = "Downloads",
                icon = Icons.Filled.Download,
                onClick = onOpenDownloads,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ── Continue Watching — driven by saved player progress ─
        val ctx = LocalContext.current
        val libraryRepo = remember { LibraryRepository.get(ctx) }
        val progress by libraryRepo.progress.collectAsStateWithLifecycle(initialValue = emptyList())
        val unwatched = remember(progress) { progress.filter { !it.watched }.sortedByDescending { it.updatedAt } }
        if (unwatched.isNotEmpty()) {
            SectionHeader(title = "Continue Watching", onMoreClick = onOpenContinueAll)
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(unwatched, key = { it.key }) { entry ->
                    ContinueWatchingCard(
                        entry = entry,
                        onClick = { onTitleClick(entry.tmdbId, entry.mediaType) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        // ── Now Playing — wide backdrop cards ───────────────────
        if (nowPlaying.isNotEmpty()) {
            SectionHeader(title = "Now Playing", onMoreClick = { onOpenCatalog(app.cyfer.streaming.android.ui.catalog.CatalogSource.NowPlaying) })
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(nowPlaying) { item ->
                    WideBackdropCard(item = item, onClick = { onTitleClick(item.id, item.media_type ?: "movie") })
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        // ── Trending Now — poster cards ─────────────────────────
        if (trendingMovies.isNotEmpty()) {
            SectionHeader(title = "Trending Now", onMoreClick = { onOpenCatalog(app.cyfer.streaming.android.ui.catalog.CatalogSource.TrendingMovies) })
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(trendingMovies) { item ->
                    PosterCard(item = item, onClick = { onTitleClick(item.id, item.media_type ?: "movie") })
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        // ── Popular Movies ──────────────────────────────────────
        if (popularMovies.isNotEmpty()) {
            SectionHeader(title = "Popular Movies", onMoreClick = { onOpenCatalog(app.cyfer.streaming.android.ui.catalog.CatalogSource.PopularMovies) })
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(popularMovies) { item ->
                    PosterCard(item = item, onClick = { onTitleClick(item.id, item.media_type ?: "movie") })
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        // ── Popular Shows ───────────────────────────────────────
        if (popularShows.isNotEmpty()) {
            SectionHeader(title = "Popular Shows", onMoreClick = { onOpenCatalog(app.cyfer.streaming.android.ui.catalog.CatalogSource.PopularShows) })
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(popularShows) { item ->
                    PosterCard(item = item, onClick = { onTitleClick(item.id, item.media_type ?: "tv") })
                }
            }
        }

        // Bottom clearance for floating pill nav bar
        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun QuickAccessPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CyferCardSurface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = CyferWhite,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = CyferWhite,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
