package app.cyfer.streaming.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import app.cyfer.streaming.android.ui.theme.CyferWhite

/**
 * TMDb-driven title logo, with a graceful text fallback when the title has
 * no logo or the image fails to load. Mirrors `src/components/title-logo.tsx`.
 *
 * @param maxHeightDp Tallest the logo image is allowed to render — matches
 * the desktop's `maxHeight` prop (default 140dp here vs 180px on desktop).
 */
@Composable
fun TitleLogo(
    title: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 140,
    fallbackStyle: TextStyle = MaterialTheme.typography.displayMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        color = CyferWhite,
    ),
    fallbackAlignment: Alignment = Alignment.BottomStart,
    textAlign: TextAlign = TextAlign.Start,
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = fallbackAlignment) {
        if (logoUrl.isNullOrBlank() || failed) {
            Text(
                text = title.uppercase(),
                style = fallbackStyle,
                color = fallbackStyle.color.takeOrElseAlpha(CyferWhite),
                textAlign = textAlign,
                maxLines = 2,
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(logoUrl).build(),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(min = 48.dp, max = maxHeightDp.dp)
                    .fillMaxWidth(0.86f),
                onError = { failed = true },
                alignment = if (fallbackAlignment == Alignment.Center) Alignment.Center else Alignment.CenterStart,
            )
        }
    }
}

private fun Color.takeOrElseAlpha(fallback: Color): Color =
    if (this == Color.Unspecified) fallback else this
