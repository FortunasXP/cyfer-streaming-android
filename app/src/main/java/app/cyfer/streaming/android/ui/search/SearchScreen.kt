package app.cyfer.streaming.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.search.RecentSearchesRepository
import app.cyfer.streaming.android.data.tmdb.TmdbItem
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val recentRepo = remember { RecentSearchesRepository.get(ctx) }
    val recent by recentRepo.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Default-state trending feed — shown when the query is empty.
    var trending by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { TmdbRepository.getTrendingMovies() }
            .onSuccess { trending = it.take(10) }
    }

    // Debounced live search — re-fires whenever the user pauses typing.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        delay(280)
        runCatching { TmdbRepository.search(q) }
            .onSuccess {
                // Filter out "person" results from TMDb multi-search;
                // we only render movies + tv.
                results = it.filter { item ->
                    val mt = item.media_type ?: "movie"
                    (mt == "movie" || mt == "tv") && !item.posterUrl.isNullOrBlank()
                }
                error = null
            }
            .onFailure {
                error = "Couldn't reach TMDb. Try again."
            }
        loading = false
    }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        // ── Search bar (focusable, debounced) ─────────────────────
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CyferCardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(52.dp)
                    .padding(horizontal = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = CyferTextTertiary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search movies, shows, anime…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CyferTextTertiary,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = CyferWhite,
                            fontSize = 16.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(CyferAccent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                val q = query.trim()
                                if (q.isNotEmpty()) {
                                    scope.launch { recentRepo.add(q) }
                                    keyboard?.hide()
                                }
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = CyferTextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            // ── Active query: results, spinner, or empty state ───
            query.trim().length >= 2 -> {
                when {
                    loading && results.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyferAccent)
                        }
                    }
                    error != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error!!, color = CyferError, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    results.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No results for \"${query.trim()}\".",
                                color = CyferTextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(results, key = { "${it.media_type ?: "x"}-${it.id}" }) { item ->
                                SearchResultCard(
                                    item = item,
                                    onClick = {
                                        scope.launch { recentRepo.add(query.trim()) }
                                        onTitleClick(item.id, item.media_type ?: "movie")
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── No active query: recent + trending ───────────────
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    if (recent.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Recent Searches",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyferWhite,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { scope.launch { recentRepo.clear() } }) {
                                    Text("Clear", color = CyferTextSecondary)
                                }
                            }
                        }
                        items(recent, key = { it }) { q ->
                            RecentRow(
                                query = q,
                                onClick = { query = q },
                                onRemove = { scope.launch { recentRepo.remove(q) } },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    if (trending.isNotEmpty()) {
                        item {
                            Text(
                                text = "Trending Today",
                                style = MaterialTheme.typography.titleMedium,
                                color = CyferWhite,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp,
                                ),
                            )
                        }
                        items(trending, key = { "trend-${it.id}" }) { item ->
                            TrendingRow(
                                item = item,
                                onClick = { onTitleClick(item.id, item.media_type ?: "movie") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = CyferTextTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = query,
            style = MaterialTheme.typography.bodyLarge,
            color = CyferTextSecondary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = CyferTextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TrendingRow(item: TmdbItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
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
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = CyferWhite,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                (item.media_type ?: "movie").let { if (it == "tv") "SERIES" else "MOVIE" },
                item.displayYear.takeIf { it.isNotEmpty() },
            ).joinToString("  ·  ")
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = CyferTextTertiary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            if (item.vote_average > 0f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = CyferGold,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (item.vote_average % 1f == 0f)
                            item.vote_average.toInt().toString()
                        else "%.1f".format(item.vote_average),
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(item: TmdbItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
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
            (item.media_type ?: "movie").let { if (it == "tv") "SERIES" else "MOVIE" },
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
