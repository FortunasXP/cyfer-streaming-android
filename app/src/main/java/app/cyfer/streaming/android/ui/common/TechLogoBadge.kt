package app.cyfer.streaming.android.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cyfer.streaming.android.data.torrent.TechTag
import app.cyfer.streaming.android.ui.theme.CyferWhite
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Renders a single [TechTag] either as its bundled brand logo or — for
 * tags with no logo (HDR, HLG, FLAC, Opus, PCM) — as a small text label.
 *
 * Brand logos ship as monochrome SVG/PNG assets designed for white
 * surfaces. To keep them readable on Cyfer's dark hero we tint every
 * opaque pixel to white via [BlendMode.SrcIn] — the same effect the
 * desktop achieves via `filter: invert(1)`.
 */
@Composable
fun TechLogoBadge(
    tag: TechTag,
    modifier: Modifier = Modifier,
    heightDp: Int = 14,
) {
    val asset = tag.assetPath
    if (asset != null) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context).data("file:///android_asset/$asset").build(),
            contentDescription = tag.label,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(CyferWhite, BlendMode.SrcIn),
            modifier = modifier.height(heightDp.dp),
        )
    } else {
        Text(
            text = tag.label,
            color = CyferWhite,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = modifier,
        )
    }
}
