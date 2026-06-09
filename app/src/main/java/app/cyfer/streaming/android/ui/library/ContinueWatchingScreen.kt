package app.cyfer.streaming.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.data.library.ProgressEntry
import app.cyfer.streaming.android.ui.common.CyferChip
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage

private enum class LibraryTab(val label: String) { Continue("Continue Watching"), History("History") }

/**
 * Dedicated full-screen view of the user's playback library. Toggles
 * between **Continue Watching** (in-progress) and **History** (titles
 * crossed the 85 % watched threshold). Mirrors the desktop's
 * `continue` route.
 */
@Composable
fun ContinueWatchingScreen(
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val ctx = LocalContext.current
    val library = remember { LibraryRepository.get(ctx) }
    val progress by library.progress.collectAsStateWithLifecycle(initialValue = emptyList())

    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(LibraryTab.Continue) }
    val sorted = remember(progress) { progress.sortedByDescending { it.updatedAt } }
    val filtered = remember(sorted, tab) {
        when (tab) {
            LibraryTab.Continue -> sorted.filter { !it.watched }
            LibraryTab.History -> sorted.filter { it.watched }
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
                .padding(start = 8.dp, end = 20.dp, bottom = 4.dp),
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
        }
        Text(
            text = "Library",
            color = CyferWhite,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTab.values().forEach { t ->
                CyferChip(
                    label = "${t.label} (${if (t == LibraryTab.Continue) sorted.count { !it.watched } else sorted.count { it.watched }})",
                    selected = t == tab,
                    onClick = { tab = t },
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (tab == LibraryTab.Continue)
                        "Nothing in progress. Start a movie or episode and it'll show up here."
                    else
                        "No completed titles yet. Episodes auto-add when you finish watching.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.key }) { entry ->
                    ProgressRow(
                        entry = entry,
                        onClick = { onTitleClick(entry.tmdbId, entry.mediaType) },
                        onRemove = {
                            scope.launch {
                                library.markUnwatched(entry.tmdbId, entry.mediaType, entry.season, entry.episode)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(entry: ProgressEntry, onClick: () -> Unit, onRemove: () -> Unit = {}) {
    val pct = if (entry.duration > 0) (entry.position / entry.duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Surface(
        onClick = onClick,
        color = CyferCardSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 112.dp, height = 64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyferDarkSurface),
                ) {
                    val img = entry.backdropUrl ?: entry.posterUrl
                    if (!img.isNullOrBlank()) {
                        AsyncImage(
                            model = img,
                            contentDescription = entry.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.seriesTitle ?: entry.title,
                        color = CyferWhite,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = if (entry.season != null && entry.episode != null)
                        "S${"%02d".format(entry.season)} E${"%02d".format(entry.episode)}"
                    else (entry.mediaType.uppercase())
                    Text(
                        text = sub,
                        color = CyferTextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    if (!entry.watched) {
                        val remainingMin = ((entry.duration - entry.position).coerceAtLeast(0.0) / 60).toInt()
                        Text(
                            text = "${remainingMin}m left",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text(
                            text = "Watched",
                            color = CyferAccent,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // Remove from list — clears the progress entry (drops it
                // from Continue, or un-marks it from History).
                Surface(
                    onClick = onRemove,
                    shape = CircleShape,
                    color = CyferDarkSurface,
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove",
                            tint = CyferTextTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (!entry.watched && entry.duration > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(CyferDarkSurface),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(pct)
                            .background(CyferAccent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
