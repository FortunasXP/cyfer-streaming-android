package app.cyfer.streaming.android.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cyfer.streaming.android.data.tmdb.TmdbItem
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import app.cyfer.streaming.android.data.tmdb.TmdbResponse
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Sealed type describing every "list of titles" view in the app — what
 * the See All buttons on Home open into, what Browse renders, and what
 * future Trakt / Kitsu catalogs will plug into.
 */
sealed interface CatalogSource {
    val title: String
    val defaultMediaType: String   // movie | tv — for tap routing

    data object TrendingMovies : CatalogSource {
        override val title = "Trending Movies"
        override val defaultMediaType = "movie"
    }
    data object TrendingShows : CatalogSource {
        override val title = "Trending Shows"
        override val defaultMediaType = "tv"
    }
    data object NowPlaying : CatalogSource {
        override val title = "Now Playing"
        override val defaultMediaType = "movie"
    }
    data object PopularMovies : CatalogSource {
        override val title = "Popular Movies"
        override val defaultMediaType = "movie"
    }
    data object PopularShows : CatalogSource {
        override val title = "Popular Shows"
        override val defaultMediaType = "tv"
    }
    data class Discover(
        val mediaType: String,           // "movie" | "tv"
        val genreId: Int? = null,
        val year: Int? = null,
        val sortBy: String = "popularity.desc",
        override val title: String = if (mediaType == "tv") "Browse Series" else "Browse Movies",
    ) : CatalogSource {
        override val defaultMediaType = mediaType
    }
}

/**
 * Paginated grid for any [CatalogSource]. Fetches page-by-page from
 * TMDb, infinite-scrolls when the user is within 4 items of the bottom.
 */
@Composable
fun CatalogScreen(
    source: CatalogSource,
    onBack: () -> Unit,
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val items = remember(source) { mutableStateListOf<TmdbItem>() }
    var page by remember(source) { mutableIntStateOf(0) }
    var totalPages by remember(source) { mutableIntStateOf(Int.MAX_VALUE) }
    var loading by remember(source) { mutableStateOf(false) }

    suspend fun loadNext() {
        if (loading || page >= totalPages) return
        loading = true
        try {
            val next = page + 1
            val resp: TmdbResponse = when (source) {
                CatalogSource.TrendingMovies -> TmdbRepository.getTrendingMoviesPaged(next)
                CatalogSource.TrendingShows -> TmdbRepository.getTrendingShowsPaged(next)
                CatalogSource.NowPlaying -> TmdbRepository.getNowPlayingPaged(next)
                CatalogSource.PopularMovies -> TmdbRepository.getPopularMoviesPaged(next)
                CatalogSource.PopularShows -> TmdbRepository.getPopularShowsPaged(next)
                is CatalogSource.Discover -> {
                    if (source.mediaType == "tv") {
                        TmdbRepository.discoverTv(next, source.genreId, source.year, source.sortBy)
                    } else {
                        TmdbRepository.discoverMovies(next, source.genreId, source.year, source.sortBy)
                    }
                }
            }
            items += resp.results
            page = resp.page
            totalPages = resp.total_pages.takeIf { it > 0 } ?: page
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(source) { loadNext() }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= items.size - 4 && items.isNotEmpty()) loadNext()
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onBack,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = CyferCardSurface,
                    contentColor = CyferWhite,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = source.title,
                style = MaterialTheme.typography.titleLarge,
                color = CyferWhite,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty() && loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyferAccent)
            }
        } else if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No titles found.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    CatalogPosterCard(
                        item = item,
                        defaultMediaType = source.defaultMediaType,
                        onClick = {
                            onTitleClick(item.id, item.media_type ?: source.defaultMediaType)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogPosterCard(
    item: TmdbItem,
    defaultMediaType: String,
    onClick: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(14.dp))
                .background(CyferCardSurface),
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent,
                            ),
                            radius = 600f,
                        ),
                    ),
            )
            Surface(
                onClick = onClick,
                color = Color.Transparent,
                modifier = Modifier.matchParentSize(),
            ) {}
            if (item.vote_average > 0f) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = CyferGold,
                            modifier = Modifier.size(11.dp),
                        )
                        Text(
                            text = if (item.vote_average % 1f == 0f)
                                item.vote_average.toInt().toString()
                            else "%.1f".format(item.vote_average),
                            color = CyferWhite,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.displayTitle,
            color = CyferWhite,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOfNotNull(
            (item.media_type ?: defaultMediaType).let { if (it == "tv") "SERIES" else "MOVIE" },
            item.displayYear.takeIf { it.isNotEmpty() },
        ).joinToString("  ·  ")
        Text(
            text = sub,
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}
