package app.cyfer.streaming.android.ui.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cyfer.streaming.android.data.tmdb.TmdbPerson
import app.cyfer.streaming.android.data.tmdb.TmdbPersonCredit
import app.cyfer.streaming.android.data.tmdb.TmdbRepository
import app.cyfer.streaming.android.ui.common.CyferChip
import app.cyfer.streaming.android.ui.common.CyferTagPill
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Locale

private enum class CreditFilter(val label: String, val mediaType: String?) {
    All("All", null),
    Movies("Movies", "movie"),
    Tv("TV", "tv"),
}

private enum class CreditSort(val label: String) {
    Newest("Newest"), Oldest("Oldest"), Rating("Rating"), Title("Title");
}

@Composable
fun PersonScreen(
    personId: Int,
    onBack: () -> Unit,
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var person by remember(personId) { mutableStateOf<TmdbPerson?>(null) }
    var credits by remember(personId) { mutableStateOf<List<TmdbPersonCredit>>(emptyList()) }
    var loading by remember(personId) { mutableStateOf(true) }
    var error by remember(personId) { mutableStateOf<String?>(null) }

    var filter by remember(personId) { mutableStateOf(CreditFilter.All) }
    var sort by remember(personId) { mutableStateOf(CreditSort.Newest) }

    // Gesture-nav phones swipe back closes the app without this — Compose
    // doesn't intercept the system back unless we explicitly ask.
    BackHandler { onBack() }

    LaunchedEffect(personId) {
        loading = true
        error = null
        runCatching {
            val p = TmdbRepository.getPersonDetails(personId)
            val c = TmdbRepository.getPersonCombinedCredits(personId).cast
            p to c
        }
            .onSuccess { (p, c) ->
                person = p
                credits = c
            }
            .onFailure { error = it.message ?: "Failed to load person." }
        loading = false
    }

    val visibleCredits = remember(credits, filter, sort) {
        val byKind = when (filter) {
            CreditFilter.All -> credits.filter { (it.media_type == "movie" || it.media_type == "tv") && !it.posterUrl.isNullOrBlank() }
            CreditFilter.Movies -> credits.filter { it.media_type == "movie" && !it.posterUrl.isNullOrBlank() }
            CreditFilter.Tv -> credits.filter { it.media_type == "tv" && !it.posterUrl.isNullOrBlank() }
        }
        when (sort) {
            CreditSort.Newest -> byKind.sortedByDescending { it.airDateMillis }
            CreditSort.Oldest -> byKind.sortedBy { it.airDateMillis }
            CreditSort.Rating -> byKind.sortedByDescending { it.vote_average }
            CreditSort.Title -> byKind.sortedBy { it.displayTitle.lowercase() }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        // Top bar — back button
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (loading && person == null) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyferAccent)
                }
            }
        } else if (person == null) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = error ?: "Person not found.",
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            item { PersonHeader(person = person!!) }
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "FILMOGRAPHY",
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Filter + sort row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CreditFilter.values().forEach { f ->
                        CyferChip(label = f.label, selected = f == filter, onClick = { filter = f })
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    CreditSort.values().forEach { s ->
                        CyferChip(label = s.label, selected = s == sort, onClick = { sort = s })
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (visibleCredits.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No credits match.",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                // 2-col grid as chunked Rows so LazyColumn stays linear.
                val rows = visibleCredits.chunked(2)
                items(rows.size, key = { i -> "credit-row-$i" }) { idx ->
                    val row = rows[idx]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        row.forEach { credit ->
                            Box(modifier = Modifier.weight(1f)) {
                                CreditCard(
                                    credit = credit,
                                    onClick = { onTitleClick(credit.id, credit.media_type ?: "movie") },
                                )
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(120.dp)) }
        }
    }
}

@Composable
private fun PersonHeader(person: TmdbPerson) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 170.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CyferCardSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (!person.profileUrl.isNullOrBlank()) {
                AsyncImage(
                    model = person.profileUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Initials fallback when TMDb has no profile photo.
                val initials = person.name
                    .split(' ')
                    .mapNotNull { it.firstOrNull()?.toString() }
                    .take(2)
                    .joinToString("")
                    .uppercase()
                Text(
                    text = initials.ifBlank { "?" },
                    color = CyferWhite,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = person.name,
                color = CyferWhite,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            person.known_for_department?.takeIf { it.isNotBlank() }?.let {
                CyferTagPill(text = it.uppercase(), background = CyferCardSurfaceLight, foreground = CyferTextSecondary)
            }
            val metaPieces = listOfNotNull(
                person.birthday?.takeIf { it.isNotBlank() }?.let { "Born ${formatDate(it)}" },
                person.deathday?.takeIf { it.isNotBlank() }?.let { "Died ${formatDate(it)}" },
                person.place_of_birth?.takeIf { it.isNotBlank() },
            )
            if (metaPieces.isNotEmpty()) {
                Text(
                    text = metaPieces.joinToString("  ·  "),
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    // Biography below the row so long bios get full width.
    person.biography?.takeIf { it.isNotBlank() }?.let { bio ->
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = bio,
            color = CyferTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun CreditCard(credit: TmdbPersonCredit, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(12.dp))
                .background(CyferCardSurface),
        ) {
            if (!credit.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = credit.posterUrl,
                    contentDescription = credit.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (credit.vote_average > 0f) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = CyferGold, modifier = Modifier.size(10.dp))
                        Text(
                            text = if (credit.vote_average % 1f == 0f)
                                credit.vote_average.toInt().toString()
                            else "%.1f".format(credit.vote_average),
                            color = CyferWhite,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = credit.displayTitle,
            color = CyferWhite,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOfNotNull(
            (credit.media_type ?: "movie").let { if (it == "tv") "SERIES" else "MOVIE" },
            credit.displayYear.takeIf { it.isNotEmpty() },
        ).joinToString("  ·  ")
        Text(
            text = sub,
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        credit.character?.takeIf { it.isNotBlank() }?.let { char ->
            Text(
                text = "as $char",
                color = CyferTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDate(iso: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val out = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    parser.parse(iso)?.let(out::format) ?: iso
}.getOrDefault(iso)
