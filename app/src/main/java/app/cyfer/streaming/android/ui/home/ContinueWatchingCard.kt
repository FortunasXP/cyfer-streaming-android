package app.cyfer.streaming.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cyfer.streaming.android.data.library.ProgressEntry
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage

/**
 * Backdrop card for the Home "Continue Watching" row — shows the title
 * backdrop, a centered play glyph, and a thin progress bar at the
 * bottom edge. Driven by a saved [ProgressEntry].
 */
@Composable
fun ContinueWatchingCard(
    entry: ProgressEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (entry.duration > 0)
        (entry.position / entry.duration).coerceIn(0.0, 1.0).toFloat()
    else 0f

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .height(130.dp),
        shape = RoundedCornerShape(12.dp),
        color = CyferCardSurface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val art = entry.backdropUrl ?: entry.posterUrl
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Bottom darken so the title is legible.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.05f),
                                Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )

            // Centered play glyph
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = CyferWhite,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Title block
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyferWhite,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildString {
                    if (entry.season != null && entry.episode != null) {
                        append("S").append("%02d".format(entry.season))
                        append(" · E").append("%02d".format(entry.episode))
                        append("  ·  ")
                    }
                    append(formatRemaining(entry.position, entry.duration))
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyferTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Slim progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(CyferAccent),
                )
            }
        }
    }
}

private fun formatRemaining(position: Double, duration: Double): String {
    val remaining = (duration - position).coerceAtLeast(0.0).toInt()
    if (remaining <= 0) return "Watched"
    val h = remaining / 3600
    val m = (remaining % 3600) / 60
    return if (h > 0) "${h}h ${m}m left" else "${m}m left"
}
