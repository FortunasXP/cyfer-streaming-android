package app.cyfer.streaming.android.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.cyfer.streaming.android.data.tmdb.TmdbGenre
import app.cyfer.streaming.android.data.tmdb.TmdbItem
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import app.cyfer.streaming.android.ui.catalog.CatalogSource
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

private enum class BrowseMediaType(val label: String, val mediaType: String) {
    Movies("Movies", "movie"),
    Series("Series", "tv"),
}

private enum class BrowseSort(val label: String, val tmdb: String) {
    Popular("Popular", "popularity.desc"),
    TopRated("Top rated", "vote_average.desc"),
    Newest("Newest", "primary_release_date.desc"),
}

@Composable
fun BrowseScreen(
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit = { _, _ -> },
    onOpenCatalog: (CatalogSource) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var media by rememberSaveable { mutableStateOf(BrowseMediaType.Movies) }
    var sort by rememberSaveable { mutableStateOf(BrowseSort.Popular) }
    var selectedGenre by rememberSaveable { mutableStateOf<Int?>(null) }

    // Genre list is per-media-type — refresh whenever the user switches.
    var movieGenres by remember { mutableStateOf<List<TmdbGenre>>(emptyList()) }
    var tvGenres by remember { mutableStateOf<List<TmdbGenre>>(emptyList()) }
    LaunchedEffect(Unit) {
        try { movieGenres = TmdbRepository.getMovieGenres() } catch (_: Throwable) {}
        try { tvGenres = TmdbRepository.getTvGenres() } catch (_: Throwable) {}
    }
    val genres = if (media == BrowseMediaType.Movies) movieGenres else tvGenres

    // ── Discover grid state (paginated) ─────────────────────────────
    val items = remember(media, sort, selectedGenre) { mutableStateListOf<TmdbItem>() }
    var page by remember(media, sort, selectedGenre) { mutableIntStateOf(0) }
    var totalPages by remember(media, sort, selectedGenre) { mutableIntStateOf(Int.MAX_VALUE) }
    var loading by remember(media, sort, selectedGenre) { mutableStateOf(false) }

    suspend fun loadNext() {
        if (loading || page >= totalPages) return
        loading = true
        try {
            val next = page + 1
            val resp = if (media == BrowseMediaType.Movies) {
                TmdbRepository.discoverMovies(next, selectedGenre, year = null, sortBy = sort.tmdb)
            } else {
                TmdbRepository.discoverTv(next, selectedGenre, year = null, sortBy = sort.tmdb)
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

    LaunchedEffect(media, sort, selectedGenre) { loadNext() }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= items.size - 4 && items.isNotEmpty()) loadNext()
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        Text(
            text = "Browse",
            style = MaterialTheme.typography.headlineLarge,
            color = CyferWhite,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Movie / Series tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrowseMediaType.values().forEach { m ->
                app.cyfer.streaming.android.ui.common.CyferTabPill(
                    label = m.label,
                    selected = media == m,
                    onClick = {
                        if (media != m) {
                            media = m
                            selectedGenre = null
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            SortDropdownPill(
                current = sort,
                onPick = { sort = it },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Genre chips — scrollable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            app.cyfer.streaming.android.ui.common.CyferChip(
                label = "All",
                selected = selectedGenre == null,
                onClick = { selectedGenre = null },
            )
            genres.forEach { g ->
                app.cyfer.streaming.android.ui.common.CyferChip(
                    label = g.name,
                    selected = selectedGenre == g.id,
                    onClick = { selectedGenre = g.id },
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (items.isEmpty() && loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyferAccent)
            }
        } else if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing to show.", color = CyferTextSecondary, style = MaterialTheme.typography.bodyMedium)
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
                    BrowsePosterCard(
                        item = item,
                        defaultMediaType = media.mediaType,
                        onClick = { onTitleClick(item.id, item.media_type ?: media.mediaType) },
                    )
                }
                item(span = { GridItemSpan(2) }) { Spacer(modifier = Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
private fun SortDropdownPill(current: BrowseSort, onPick: (BrowseSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(14.dp),
            color = CyferCardSurface,
        ) {
            Text(
                text = current.label,
                color = CyferWhite,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = CyferDarkSurface,
        ) {
            BrowseSort.values().forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label,
                            color = if (s == current) CyferAccent else CyferWhite,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    onClick = { onPick(s); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun BrowsePosterCard(
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
                            colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
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
                            Icons.Default.Star,
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
        Text(
            text = if (defaultMediaType == "tv") "SERIES" else "MOVIE",
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}
