package app.cyfer.streaming.android.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cyfer.streaming.android.data.torrent.engine.TorrentEngine
import app.cyfer.streaming.android.data.torrent.engine.TorrentForegroundService
import app.cyfer.streaming.android.data.torrent.engine.TorrentStreamingServer
import app.cyfer.streaming.android.data.kitsu.AnimeTitle
import app.cyfer.streaming.android.ui.anime.AnimeDetailScreen
import app.cyfer.streaming.android.ui.anime.AnimeScreen
import app.cyfer.streaming.android.ui.browse.BrowseScreen
import app.cyfer.streaming.android.ui.calendar.CalendarScreen
import app.cyfer.streaming.android.ui.catalog.CatalogScreen
import app.cyfer.streaming.android.ui.catalog.CatalogSource
import app.cyfer.streaming.android.ui.downloads.DownloadsScreen
import app.cyfer.streaming.android.ui.home.HomeScreen
import app.cyfer.streaming.android.ui.mylist.MyListScreen
import app.cyfer.streaming.android.ui.person.PersonScreen
import app.cyfer.streaming.android.ui.player.PlayerScreen
import app.cyfer.streaming.android.ui.search.SearchScreen
import app.cyfer.streaming.android.ui.settings.SettingsScreen
import app.cyfer.streaming.android.ui.sources.SourcePickerRequest
import app.cyfer.streaming.android.ui.sources.SourcePickerSheet
import app.cyfer.streaming.android.ui.theme.CyferBlack
import app.cyfer.streaming.android.ui.theme.CyferCardSurfaceLight
import app.cyfer.streaming.android.ui.theme.CyferNavBarBackground
import app.cyfer.streaming.android.ui.theme.CyferTextTertiary
import app.cyfer.streaming.android.ui.theme.CyferWhite
import app.cyfer.streaming.android.ui.details.TitleDetailsScreen

data class DetailsRequest(
    val tmdbId: Int,
    val mediaType: String
)

data class PlayerRequest(
    val url: String,
    val title: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val mediaType: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val initialPosition: Double = 0.0,
)

@Composable
fun CyferApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var playerRequest by remember { mutableStateOf<PlayerRequest?>(null) }
    var detailsRequest by remember { mutableStateOf<DetailsRequest?>(null) }
    var sourcePickerRequest by remember { mutableStateOf<SourcePickerRequest?>(null) }
    var catalogSource by remember { mutableStateOf<CatalogSource?>(null) }
    var animeDetail by remember { mutableStateOf<AnimeTitle?>(null) }
    var personId by remember { mutableStateOf<Int?>(null) }
    // Secondary screens reachable from the Home quick-access strip
    // rather than the bottom nav. Booleans because there's nothing
    // mode-y about them — they either own the screen or they don't.
    var animeOpen by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val openSources: (SourcePickerRequest) -> Unit = { sourcePickerRequest = it }

    // Tear down the on-device torrent engine when the player closes.
    // Local-torrent URLs come from our embedded HTTP server, so any
    // stream starting with `http://127.0.0.1:` is one of ours.
    val closePlayer: () -> Unit = {
        val wasLocalTorrent = playerRequest?.url?.startsWith("http://127.0.0.1:") == true
        playerRequest = null
        if (wasLocalTorrent) {
            TorrentStreamingServer.shared().unmount()
            TorrentEngine.stopAllActive()
            TorrentForegroundService.stop(context)
        }
    }

    if (playerRequest != null) {
        val r = playerRequest!!
        PlayerScreen(
            url = r.url,
            title = r.title,
            backdropUrl = r.backdropUrl,
            posterUrl = r.posterUrl,
            mediaType = r.mediaType,
            tmdbId = r.tmdbId,
            imdbId = r.imdbId,
            season = r.season,
            episode = r.episode,
            initialPosition = r.initialPosition,
            onBack = closePlayer,
            onAdvanceToNext = { nextRequest ->
                // Close the current player (tears down the torrent),
                // then hand the next-episode SourcePickerRequest to the
                // existing picker → player → autoplay loop.
                closePlayer()
                sourcePickerRequest = nextRequest
            },
        )
    } else if (personId != null) {
        PersonScreen(
            personId = personId!!,
            onBack = { personId = null },
            onTitleClick = { id, type ->
                personId = null
                detailsRequest = DetailsRequest(id, type)
            },
        )
    } else if (detailsRequest != null) {
        TitleDetailsScreen(
            tmdbId = detailsRequest!!.tmdbId,
            mediaType = detailsRequest!!.mediaType,
            onBack = { detailsRequest = null },
            onRequestSources = openSources,
            onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
            onPersonClick = { id -> personId = id },
        )
    } else if (catalogSource != null) {
        CatalogScreen(
            source = catalogSource!!,
            onBack = { catalogSource = null },
            onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
        )
    } else if (animeDetail != null) {
        AnimeDetailScreen(
            item = animeDetail!!,
            onBack = { animeDetail = null },
            onRequestSources = openSources,
        )
    } else if (animeOpen) {
        AnimeScreen(
            onRequestSources = openSources,
            onOpenDetail = { animeDetail = it },
            onBack = { animeOpen = false },
            modifier = Modifier.fillMaxSize(),
        )
        androidx.activity.compose.BackHandler { animeOpen = false }
    } else if (calendarOpen) {
        CalendarScreen(
            onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
            onBack = { calendarOpen = false },
            modifier = Modifier.fillMaxSize(),
        )
        androidx.activity.compose.BackHandler { calendarOpen = false }
    } else if (downloadsOpen) {
        val playScope = rememberCoroutineScope()
        DownloadsScreen(
            onBack = { downloadsOpen = false },
            onPlay = { entry ->
                // Resolve the pinned magnet through the local engine —
                // since it's already in sessions[infoHash], this returns
                // the cached session instantly and MPV plays from disk.
                playScope.launch {
                    runCatching {
                        app.cyfer.streaming.android.data.torrent.engine.resolveLocalTorrent(
                            context = context,
                            payload = app.cyfer.streaming.android.data.debrid.DebridResolveRequest(
                                source = entry.magnet,
                                title = entry.title,
                                season = entry.season,
                                episode = entry.episode,
                                seriesTitle = entry.title,
                                timeoutMs = 15_000L,
                            ),
                        )
                    }.onSuccess { media ->
                        downloadsOpen = false
                        playerRequest = PlayerRequest(
                            url = media.url,
                            title = entry.title,
                            backdropUrl = entry.backdropUrl,
                            posterUrl = entry.posterUrl,
                            mediaType = entry.mediaType,
                            tmdbId = entry.tmdbId,
                            season = entry.season,
                            episode = entry.episode,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        androidx.activity.compose.BackHandler { downloadsOpen = false }
    } else if (libraryOpen) {
        app.cyfer.streaming.android.ui.library.ContinueWatchingScreen(
            onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
            onBack = { libraryOpen = false },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyferBlack)
        ) {
            // ── Screen content ──────────────────────────────────────
            when (selectedTab) {
                0 -> HomeScreen(
                    onRequestSources = openSources,
                    onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
                    onOpenCatalog = { catalogSource = it },
                    onOpenAnime = { animeOpen = true },
                    onOpenCalendar = { calendarOpen = true },
                    onOpenDownloads = { downloadsOpen = true },
                    onOpenContinueAll = { libraryOpen = true },
                    modifier = Modifier.fillMaxSize(),
                )
                1 -> BrowseScreen(
                    onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
                    onOpenCatalog = { catalogSource = it },
                    modifier = Modifier.fillMaxSize(),
                )
                2 -> SearchScreen(
                    onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
                    modifier = Modifier.fillMaxSize(),
                )
                3 -> MyListScreen(
                    onTitleClick = { id, type -> detailsRequest = DetailsRequest(id, type) },
                    modifier = Modifier.fillMaxSize(),
                )
                4 -> SettingsScreen(modifier = Modifier.fillMaxSize())
            }

            // ── Floating pill navigation bar — 5 tabs, labels always on ─
            // The Material NavigationBar's stock layout adds chunky outer
            // padding and an indicator pill that fights the floating
            // surface, so we render our own row instead. Each item is a
            // vertical icon-over-label stack with an accent dot under
            // the selected one — clean, predictable, no truncation.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(28.dp),
                color = CyferNavBarBackground.copy(alpha = 0.92f),
                shadowElevation = 16.dp,
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    bottomNavScreens.forEachIndexed { index, screen ->
                        val selected = selectedTab == index
                        NavItem(
                            selected = selected,
                            label = screen.label,
                            icon = if (selected) screen.selectedIcon else screen.unselectedIcon,
                            onClick = { selectedTab = index },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    sourcePickerRequest?.let { req ->
        SourcePickerSheet(
            request = req,
            onDismiss = { sourcePickerRequest = null },
            onResolved = { url, title, initialPosition ->
                sourcePickerRequest = null
                playerRequest = PlayerRequest(
                    url = url,
                    title = title,
                    backdropUrl = req.backdropUrl,
                    posterUrl = req.posterUrl,
                    mediaType = when (req.mediaType) {
                        app.cyfer.streaming.android.data.torrent.TorrentMediaType.tv -> "tv"
                        app.cyfer.streaming.android.data.torrent.TorrentMediaType.anime -> "anime"
                        else -> "movie"
                    },
                    tmdbId = req.tmdbId,
                    imdbId = req.imdbId,
                    season = req.season,
                    episode = req.episode,
                    initialPosition = initialPosition,
                )
            },
        )
    }
}

/**
 * Single nav cell — icon + label stacked, accent dot under the active
 * one, no Material indicator pill or ripple chunk to fight with.
 */
@Composable
private fun NavItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) CyferWhite else CyferTextTertiary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = if (selected) CyferWhite else CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 14.dp else 0.dp)
                .background(
                    color = if (selected) CyferWhite else Color.Transparent,
                    shape = RoundedCornerShape(1.dp),
                ),
        )
    }
}
