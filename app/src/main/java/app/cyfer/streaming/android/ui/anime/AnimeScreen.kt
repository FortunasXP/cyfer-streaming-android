package app.cyfer.streaming.android.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
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
import app.cyfer.streaming.android.data.kitsu.AnimeHomeFeed
import app.cyfer.streaming.android.data.kitsu.AnimeTitle
import app.cyfer.streaming.android.data.kitsu.KitsuRepository
import app.cyfer.streaming.android.data.torrent.TorrentMediaType
import app.cyfer.streaming.android.ui.common.CyferChip
import app.cyfer.streaming.android.ui.sources.SourcePickerRequest
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeScreen(
    onRequestSources: (SourcePickerRequest) -> Unit,
    onOpenDetail: (AnimeTitle) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var feed by remember { mutableStateOf<AnimeHomeFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

    suspend fun loadFeed(force: Boolean) {
        runCatching { KitsuRepository.getHome(forceRefresh = force) }
            .onSuccess { feed = it; error = null }
            .onFailure { if (feed == null) error = "Failed to load anime data from Kitsu." }
    }

    // First composition goes through the repository's 15-min TTL cache —
    // instant when hopping tabs, but re-pulled once stale so the page
    // follows Kitsu's trending/seasonal data instead of freezing.
    LaunchedEffect(Unit) { loadFeed(force = false) }

    // ── Discover state ───────────────────────────────────────
    var genre by remember { mutableStateOf("All") }
    var status by remember { mutableStateOf("All") }
    var season by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("Top rated") }
    var discoverItems by remember { mutableStateOf<List<AnimeTitle>>(emptyList()) }
    var discoverPage by remember { mutableIntStateOf(0) }
    var discoverLoading by remember { mutableStateOf(false) }
    var discoverHasMore by remember { mutableStateOf(true) }

    suspend fun loadDiscover(reset: Boolean) {
        if (discoverLoading) return
        discoverLoading = true
        // On a filter change we want to flush the previous result set
        // immediately so the user sees the spinner instead of stale rows.
        if (reset) discoverItems = emptyList()
        val nextPage = if (reset) 0 else discoverPage + 1
        val res = runCatching {
            KitsuRepository.discover(
                page = nextPage,
                genre = genre,
                season = season,
                status = status,
                sort = sort,
            )
        }.getOrNull()
        if (res != null) {
            discoverItems = if (reset) res.items else discoverItems + res.items
            discoverPage = nextPage
            discoverHasMore = res.hasMore
        }
        discoverLoading = false
    }

    LaunchedEffect(genre, status, season, sort) { loadDiscover(reset = true) }

    // PullToRefreshBox is a Box, so it doubles as the overlay host for
    // the top-left back button. Pull busts the Kitsu TTL cache — the
    // direct "show me what Kitsu says right now" gesture.
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshScope.launch {
                refreshing = true
                loadFeed(force = true)
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyferBlack)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Hero rotator from Spotlight ─────────────────────────
        val spotlight = feed?.spotlight.orEmpty()
        var spotIdx by remember { mutableIntStateOf(0) }
        // Defensive clamp — if the feed reloads and shrinks (e.g. Kitsu
        // returns fewer spotlight items than before) we reset to 0
        // rather than rendering a blank hero from an out-of-range index.
        LaunchedEffect(spotlight.size) {
            if (spotIdx >= spotlight.size) spotIdx = 0
            if (spotlight.size > 1) {
                while (true) {
                    delay(9000)
                    spotIdx = (spotIdx + 1) % spotlight.size
                }
            }
        }
        val hero = spotlight.getOrNull(spotIdx)
        if (hero != null) {
            AnimeHero(
                item = hero,
                onPlay = { onOpenDetail(hero) },
            )
        } else if (feed == null && error == null) {
            // Loading splash for first paint.
            Box(
                modifier = Modifier.fillMaxWidth().height(520.dp).background(CyferDarkSurface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = CyferAccent)
            }
        }

        if (error != null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(error!!, color = CyferError, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        feed?.let { f ->
            if (f.trending.isNotEmpty()) {
                AnimeRow(
                    title = "Trending now",
                    eyebrow = "TOP 10 THIS WEEK",
                    items = f.trending,
                    numbered = true,
                    onClick = { onOpenDetail(it) },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            if (f.seasonal.isNotEmpty()) {
                AnimeRow(
                    title = "This season",
                    eyebrow = f.seasonLabel.uppercase(),
                    items = f.seasonal,
                    onClick = { onOpenDetail(it) },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            if (f.airing.isNotEmpty()) {
                AnimeRow(
                    title = "Currently airing",
                    eyebrow = "FRESH EPISODES",
                    items = f.airing,
                    onClick = { onOpenDetail(it) },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            if (f.upcoming.isNotEmpty()) {
                AnimeRow(
                    title = "Most anticipated",
                    eyebrow = "COMING SOON",
                    items = f.upcoming,
                    onClick = { onOpenDetail(it) },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            if (f.topRated.isNotEmpty()) {
                AnimeRow(
                    title = "Best of ${f.yearLabel}",
                    eyebrow = "HIGHEST RATED THIS YEAR",
                    items = f.topRated,
                    onClick = { onOpenDetail(it) },
                )
                Spacer(modifier = Modifier.height(28.dp))
            }
        }

        // ── Discover section ────────────────────────────────────
        DiscoverFilters(
            genre = genre, onGenre = { genre = it },
            status = status, onStatus = { status = it },
            season = season, onSeason = { season = it },
            sort = sort, onSort = { sort = it },
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (discoverItems.isEmpty() && discoverLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyferAccent)
            }
        } else if (discoverItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.CenterStart) {
                Text("No anime match these filters.", color = CyferTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // Two-column staggered grid as a vertical sequence of rows
            // (avoids nesting a LazyVerticalGrid inside this verticalScroll
            // which Compose forbids).
            val rows = discoverItems.chunked(2)
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { card ->
                            Box(modifier = Modifier.weight(1f)) {
                                DiscoverPosterCard(item = card, onClick = { onOpenDetail(card) })
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (discoverHasMore) {
                Spacer(modifier = Modifier.height(18.dp))
                val scope = rememberCoroutineScope()
                Surface(
                    onClick = { scope.launch { loadDiscover(reset = false) } },
                    shape = RoundedCornerShape(20.dp),
                    color = CyferCardSurface,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 20.dp),
                ) {
                    if (discoverLoading) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(color = CyferAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Text("Loading…", color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            text = "Load more",
                            color = CyferWhite,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        // Floating-nav clearance
        Spacer(modifier = Modifier.height(120.dp))
    }

    // Overlay back button — only when a parent gave us an onBack.
    if (onBack != null) {
        FilledIconButton(
            onClick = onBack,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.55f),
                contentColor = CyferWhite,
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
    }
    }
}

private val ANIME_GENRES = listOf("All", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mecha", "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller", "Music", "Psychological")
private val ANIME_SEASONS = listOf("All", "Winter", "Spring", "Summer", "Fall")
private val ANIME_STATUSES = listOf("All", "Airing", "Finished")
private val ANIME_SORTS = listOf("Top rated", "Newest", "A-Z", "Episodes")

@Composable
private fun DiscoverFilters(
    genre: String, onGenre: (String) -> Unit,
    status: String, onStatus: (String) -> Unit,
    season: String, onSeason: (String) -> Unit,
    sort: String, onSort: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "DISCOVER",
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = "Browse by filter",
                color = CyferWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        FilterRowScroll(values = ANIME_GENRES, current = genre, onPick = onGenre)
        FilterRowScroll(values = ANIME_SEASONS, current = season, onPick = onSeason)
        FilterRowScroll(values = ANIME_STATUSES, current = status, onPick = onStatus)
        FilterRowScroll(values = ANIME_SORTS, current = sort, onPick = onSort)
    }
}

@Composable
private fun FilterRowScroll(values: List<String>, current: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.forEach { v ->
            CyferChip(label = v, selected = v == current, onClick = { onPick(v) })
        }
    }
}

@Composable
private fun DiscoverPosterCard(item: AnimeTitle, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
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
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.status == "Airing") {
                Box(modifier = Modifier.padding(8.dp)) { AiringPill() }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            color = CyferWhite,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOfNotNull(
            item.voteAverage?.let { "★ %.1f".format(it) },
            if (item.animeKind == "movie") "Movie" else item.episodeCount?.let { "$it eps" },
            item.year,
        ).joinToString("  ·  ")
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AnimeHero(item: AnimeTitle, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(560.dp),
    ) {
        val bg = item.backdropUrl ?: item.posterUrl
        if (!bg.isNullOrBlank()) {
            AsyncImage(
                model = bg,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Top-to-bottom gradient ensures legibility over bright backdrops.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent,
                            CyferBlack.copy(alpha = 0.5f),
                            CyferBlack,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.status == "Airing") {
                    AiringPill()
                }
                Text(
                    text = "ANIME",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = CyferGold, modifier = Modifier.size(14.dp))
                        Text(
                            text = "%.1f".format(rating),
                            color = CyferWhite,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                item.year?.let {
                    Text(it, color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                item.episodeCount?.let {
                    Text("$it eps", color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                if (item.animeKind == "movie") {
                    Text("Movie", color = CyferTextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (item.overview.isNotBlank()) {
                Text(
                    text = item.overview,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onPlay,
                    shape = RoundedCornerShape(28.dp),
                    color = CyferAccent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = CyferBlack)
                        Text(
                            text = "Play",
                            color = CyferBlack,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = CyferWhite)
                        Text(
                            text = "My List",
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
private fun AiringPill() {
    Surface(color = CyferAccent, shape = RoundedCornerShape(6.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(CyferBlack),
            )
            Text(
                text = "AIRING",
                color = CyferBlack,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

@Composable
private fun AnimeRow(
    title: String,
    eyebrow: String,
    items: List<AnimeTitle>,
    numbered: Boolean = false,
    onClick: (AnimeTitle) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = eyebrow,
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = title,
                color = CyferWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // `itemsIndexed` so we can pass the rank without an O(n)
            // `items.indexOf(item)` lookup on every recomposition.
            itemsIndexed(items, key = { _, it -> "anime-${it.id}" }) { index, item ->
                if (numbered) {
                    NumberedAnimeCard(rank = index + 1, item = item, onClick = { onClick(item) })
                } else {
                    AnimePosterCard(item = item, onClick = { onClick(item) })
                }
            }
        }
    }
}

@Composable
private fun AnimePosterCard(item: AnimeTitle, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Standard 2:3 poster aspect (Kitsu / TMDb assets) — fixed
                // 200 dp height clipped portrait posters by ~6 dp.
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(12.dp))
                .background(CyferCardSurface),
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.status == "Airing") {
                Box(modifier = Modifier.padding(8.dp)) { AiringPill() }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            color = CyferWhite,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = buildString {
            item.voteAverage?.let { append("★ %.1f".format(it)) }
            if (item.voteAverage != null && (item.episodeCount != null || item.animeKind == "movie")) append("  ·  ")
            if (item.animeKind == "movie") append("Movie")
            else item.episodeCount?.let { append("$it eps") }
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NumberedAnimeCard(rank: Int, item: AnimeTitle, onClick: () -> Unit) {
    // Big numbered tile mimicking Netflix-style Top 10 rail — the desktop's
    // signature Trending row visualisation.
    Row(
        modifier = Modifier
            .height(180.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            color = CyferWhite,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(end = 6.dp),
        )
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(170.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CyferCardSurface),
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
