package app.cyfer.streaming.android.ui.mylist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.data.library.WatchlistEntry
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage

private enum class MyListFilter(val label: String, val mediaType: String?) {
    All("All", null),
    Movies("Movies", "movie"),
    Series("Series", "tv"),
    Anime("Anime", "anime"),
}

private enum class MyListSort(val label: String) {
    Recent("Recent"),
    TitleAsc("Title"),
    YearDesc("Year"),
}

@Composable
fun MyListScreen(
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val watchlist by repo.watchlist.collectAsStateWithLifecycle(initialValue = emptyList())

    var filter by rememberSaveable { mutableStateOf(MyListFilter.All) }
    var sort by rememberSaveable { mutableStateOf(MyListSort.Recent) }

    val filtered = remember(watchlist, filter, sort) {
        val byFilter = if (filter.mediaType == null) watchlist
        else watchlist.filter { it.mediaType == filter.mediaType }
        when (sort) {
            MyListSort.Recent -> byFilter.sortedByDescending { it.addedAt }
            MyListSort.TitleAsc -> byFilter.sortedBy { it.title.lowercase() }
            MyListSort.YearDesc -> byFilter.sortedByDescending { it.year ?: "" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        // ── Title bar ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "My List",
                style = MaterialTheme.typography.headlineLarge,
                color = CyferWhite,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { /* TODO: search inside watchlist */ }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = CyferWhite,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Filter pills + Sort pill ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MyListFilter.values().forEach { f ->
                app.cyfer.streaming.android.ui.common.CyferChip(
                    label = f.label,
                    selected = filter == f,
                    onClick = { filter = f },
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            SortPill(
                label = sort.label,
                onClick = {
                    sort = when (sort) {
                        MyListSort.Recent -> MyListSort.TitleAsc
                        MyListSort.TitleAsc -> MyListSort.YearDesc
                        MyListSort.YearDesc -> MyListSort.Recent
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Grid ────────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (watchlist.isEmpty())
                        "Nothing saved yet. Tap the + on a title's details page to add it to your list."
                    else
                        "No titles match this filter.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(filtered, key = { "${it.mediaType}:${it.tmdbId}" }) { entry ->
                    PosterGridCard(
                        entry = entry,
                        onClick = { onTitleClick(entry.tmdbId, entry.mediaType) },
                    )
                }
                item(span = { GridItemSpan(2) }) { Spacer(modifier = Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
private fun SortPill(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = CyferCardSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                tint = CyferWhite,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = CyferWhite,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PosterGridCard(
    entry: WatchlistEntry,
    onClick: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f) // 2:3 poster aspect ratio
                .clip(RoundedCornerShape(14.dp))
                .background(CyferCardSurface),
        ) {
            if (!entry.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.posterUrl,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
            // Subtle radial shimmer so monochrome posters still look alive.
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
            // Tap layer
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Transparent)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                Surface(
                    onClick = onClick,
                    color = Color.Transparent,
                    modifier = Modifier.matchParentSize(),
                ) {}
            }
            // ★ rating badge top-left
            if (entry.voteAverage > 0f) {
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
                            text = formatRating(entry.voteAverage),
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
            text = entry.title,
            color = CyferWhite,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = mediaLabel(entry.mediaType),
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
    }
}

private fun formatRating(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)

private fun mediaLabel(mediaType: String): String = when (mediaType) {
    "tv" -> "SERIES"
    "anime" -> "ANIME"
    else -> "MOVIE"
}
