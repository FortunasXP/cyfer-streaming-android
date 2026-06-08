package app.cyfer.streaming.android.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.downloads.DownloadEntry
import app.cyfer.streaming.android.data.downloads.DownloadStatus
import app.cyfer.streaming.android.data.downloads.DownloadsCoordinator
import app.cyfer.streaming.android.data.downloads.DownloadsRepository
import app.cyfer.streaming.android.ui.common.CyferTagPill
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Offline-first Downloads page.
 *
 *  • Sections split by status — **Available offline** (Completed) at the
 *    top, **Downloading** in the middle, **Paused / Error** at the bottom.
 *  • Completed cards are big tappable hero rows with a prominent Play
 *    button — works without network because the file is on disk.
 *  • In-progress cards show live progress + the same Play affordance —
 *    libtorrent serves what it has, so partial-play is possible too.
 *  • Three-dot more menu hosts Pause / Resume / Delete to keep the
 *    primary action (Play) unambiguous.
 */
@Composable
fun DownloadsScreen(
    onBack: (() -> Unit)? = null,
    onPlay: (DownloadEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { DownloadsRepository.get(context) }
    val entries by repo.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { DownloadsCoordinator.ensureStarted(context) }

    // Bucket entries by status group so the offline section sits on top.
    val available = remember(entries) { entries.filter { it.status == DownloadStatus.Completed } }
    val downloading = remember(entries) {
        entries.filter {
            it.status == DownloadStatus.Downloading ||
                it.status == DownloadStatus.Queued ||
                it.status == DownloadStatus.Checking
        }
    }
    val other = remember(entries) {
        entries.filter { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Error }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        // Top bar — back button (only when shown as overlay) + title
        if (onBack != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, bottom = 4.dp),
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
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "Downloads",
                color = CyferWhite,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            // Offline-first messaging — tells users what's playable without network.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = CyferTextTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "${available.size} available offline  ·  ${downloading.size} downloading",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (entries.isEmpty()) {
            EmptyDownloads()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (available.isNotEmpty()) {
                item { SectionHeader("Available offline", available.size) }
                items(available, key = { it.infoHash }) { entry ->
                    AvailableCard(
                        entry = entry,
                        onPlay = { onPlay(entry) },
                        onRemove = { scope.launch { DownloadsCoordinator.remove(context, entry.infoHash) } },
                    )
                }
            }
            if (downloading.isNotEmpty()) {
                item { SectionHeader("Downloading", downloading.size) }
                items(downloading, key = { it.infoHash }) { entry ->
                    InProgressCard(
                        entry = entry,
                        onPlay = { onPlay(entry) },
                        onPause = { DownloadsCoordinator.pause(entry.infoHash) },
                        onRemove = { scope.launch { DownloadsCoordinator.remove(context, entry.infoHash) } },
                    )
                }
            }
            if (other.isNotEmpty()) {
                item { SectionHeader("Paused / Errors", other.size) }
                items(other, key = { it.infoHash }) { entry ->
                    InProgressCard(
                        entry = entry,
                        onPlay = { onPlay(entry) },
                        onPause = {
                            if (entry.status == DownloadStatus.Paused) DownloadsCoordinator.resume(entry.infoHash)
                            else DownloadsCoordinator.pause(entry.infoHash)
                        },
                        onRemove = { scope.launch { DownloadsCoordinator.remove(context, entry.infoHash) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Text(
            text = count.toString(),
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyDownloads() {
    Box(modifier = Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = CyferTextTertiary,
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = "Nothing saved offline",
                color = CyferWhite,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Tap the download icon next to any torrent source in the picker. Saved items appear here and play even when the device is offline.",
                color = CyferTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Big hero card for a completed download — full-width artwork, gradient
 * overlay, prominent Play button. The whole card is the Play target;
 * the Close icon is the only secondary action visible.
 */
@Composable
private fun AvailableCard(
    entry: DownloadEntry,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(14.dp),
        color = CyferCardSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.height(140.dp)) {
            val art = entry.backdropUrl ?: entry.posterUrl
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Left-anchored darkening gradient so text + Play button stay legible.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.85f),
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.2f),
                            ),
                        ),
                    ),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onPlay,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferAccent,
                        contentColor = CyferBlack,
                    ),
                    modifier = Modifier.size(54.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = entry.title,
                        color = CyferWhite,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = buildList {
                        add(entry.mediaType.uppercase())
                        if (entry.season != null && entry.episode != null)
                            add("S${"%02d".format(entry.season)} E${"%02d".format(entry.episode)}")
                        formatBytes(entry.sizeBytes)?.let { add(it) }
                    }.joinToString("  ·  ")
                    Text(
                        text = sub,
                        color = CyferWhite.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    CyferTagPill(
                        text = "AVAILABLE OFFLINE",
                        background = Color(0xFF00C853),
                        foreground = CyferBlack,
                    )
                }
                FilledIconButton(
                    onClick = onRemove,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        contentColor = CyferWhite,
                    ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Compact card for downloads still in flight — poster, title, live progress bar. */
@Composable
private fun InProgressCard(
    entry: DownloadEntry,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CyferCardSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 90.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyferDarkSurface),
                ) {
                    if (!entry.posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = entry.posterUrl,
                            contentDescription = entry.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = entry.title,
                        color = CyferWhite,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        entry.mediaType.uppercase().takeIf { it.isNotEmpty() },
                        if (entry.season != null && entry.episode != null)
                            "S${"%02d".format(entry.season)} E${"%02d".format(entry.episode)}"
                        else null,
                    ).joinToString("  ·  ")
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            color = CyferTextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                    }
                    Text(
                        text = entry.releaseTitle,
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(status = entry.status)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Live progress bar — only useful while bytes are flowing.
            val done = entry.downloadedBytes.coerceAtLeast(0L)
            val total = entry.sizeBytes.coerceAtLeast(0L)
            val pct = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CyferDarkSurface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pct)
                        .background(
                            if (entry.status == DownloadStatus.Error) CyferError else CyferAccent,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatBytes(done) ?: "0 B"} / ${formatBytes(total) ?: "—"}  ·  ${(pct * 100).toInt()}%",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // Play partial — libtorrent can serve what's downloaded.
                FilledIconButton(
                    onClick = onPlay,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferAccent,
                        contentColor = CyferBlack,
                    ),
                    modifier = Modifier.size(32.dp),
                    enabled = done > 0,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                FilledIconButton(
                    onClick = onPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferDarkSurface,
                        contentColor = CyferWhite,
                    ),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (entry.status == DownloadStatus.Paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (entry.status == DownloadStatus.Paused) "Resume" else "Pause",
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                FilledIconButton(
                    onClick = onRemove,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferDarkSurface,
                        contentColor = CyferError,
                    ),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: DownloadStatus) {
    val (label, color, fg) = when (status) {
        DownloadStatus.Queued -> Triple("QUEUED", CyferCardSurfaceLight, CyferWhite)
        DownloadStatus.Checking -> Triple("CHECKING", Color(0xFFFFA000), CyferBlack)
        DownloadStatus.Downloading -> Triple("DOWNLOADING", Color(0xFFFFB300), CyferBlack)
        DownloadStatus.Paused -> Triple("PAUSED", CyferTextTertiary, CyferWhite)
        DownloadStatus.Completed -> Triple("READY", CyferAccent, CyferBlack)
        DownloadStatus.Error -> Triple("ERROR", CyferError, CyferWhite)
    }
    CyferTagPill(text = label, background = color, foreground = fg)
}

private fun formatBytes(bytes: Long): String? {
    if (bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024.0; unit++ }
    return if (value >= 10 || unit == 0) "${value.toInt()} ${units[unit]}"
        else "%.1f %s".format(value, units[unit])
}
