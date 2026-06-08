package app.cyfer.streaming.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cyfer.streaming.android.data.tmdb.TmdbItem
import app.cyfer.streaming.android.ui.common.TitleLogo
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage

/**
 * Home Hero card — mirrors the desktop `hr1-hero` layout from
 * `src/components/streaming-desktop.tsx`. Uses [TitleLogo] in place of the
 * raw title text when TMDb has a logo for the title.
 */
@Composable
fun HeroCard(
    item: TmdbItem,
    logoUrl: String?,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(560.dp),
    ) {
        item.backdropUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Vertical scrim — transparent at top, fades to black at the bottom.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.45f to CyferBlack.copy(alpha = 0.25f),
                            0.7f to CyferBlack.copy(alpha = 0.8f),
                            1.0f to CyferBlack,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            // Eyebrow — small spaced caps, mirrors the desktop "tick · date · curated" row
            Text(
                text = if (item.media_type == "tv") "TRENDING SHOW" else "TRENDING FILM",
                style = MaterialTheme.typography.labelMedium,
                color = CyferTextSecondary,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            TitleLogo(
                title = item.displayTitle,
                logoUrl = logoUrl,
                maxHeightDp = 110,
                fallbackStyle = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = CyferWhite,
                ),
            )

            if (!item.overview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyferTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Meta row: year · MediaLabel · ★ rating
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val parts = buildList {
                    if (item.displayYear.isNotEmpty()) add(item.displayYear)
                    add(if (item.media_type == "tv") "Series" else "Film")
                }
                parts.forEachIndexed { index, value ->
                    if (index > 0) MetaSeparator()
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyferWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (item.vote_average > 0f) {
                    MetaSeparator()
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = CyferGold,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = item.ratingFormatted,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyferWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyferWhite,
                        contentColor = CyferBlack,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play feature", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(12.dp))

                FilledIconButton(
                    onClick = onAdd,
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferCardSurface.copy(alpha = 0.85f),
                        contentColor = CyferWhite,
                    ),
                ) {
                    Icon(Icons.Filled.Add, "Add to list")
                }
            }
        }
    }
}

@Composable
private fun MetaSeparator() {
    Text(
        text = "·",
        style = MaterialTheme.typography.bodyMedium,
        color = CyferTextTertiary,
    )
}
