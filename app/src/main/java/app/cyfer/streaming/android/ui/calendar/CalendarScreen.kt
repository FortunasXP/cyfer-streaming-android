package app.cyfer.streaming.android.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.calendar.CalendarEpisode
import app.cyfer.streaming.android.data.calendar.CalendarRepository
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class CalendarFilter(val label: String) { All("All"), Aired("Aired"), Upcoming("Upcoming") }

@Composable
fun CalendarScreen(
    onTitleClick: (tmdbId: Int, mediaType: String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val libraryRepo = remember { LibraryRepository.get(ctx) }
    val watchlist by libraryRepo.watchlist.collectAsStateWithLifecycle(initialValue = emptyList())

    var episodes by remember { mutableStateOf<List<CalendarEpisode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(watchlist) {
        loading = true
        episodes = runCatching { CalendarRepository.getUpcomingEpisodes(watchlist) }
            .getOrElse { emptyList() }
        loading = false
    }

    var cursor by remember { mutableStateOf(monthStart(Date())) }
    var selected by remember { mutableStateOf(Date()) }
    var filter by remember { mutableStateOf(CalendarFilter.All) }

    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    val byDay = remember(episodes) { episodes.groupBy { CalendarRepository.dayKey(it.airDateMillis) } }
    val selectedDayKey = remember(selected) { CalendarRepository.dayKey(selected.time) }
    val selectedDayEpisodes = remember(selectedDayKey, byDay, filter) {
        val all = byDay[selectedDayKey].orEmpty()
        when (filter) {
            CalendarFilter.All -> all
            CalendarFilter.Aired -> all.filter { it.airDateMillis < startOfToday() }
            CalendarFilter.Upcoming -> all.filter { it.airDateMillis >= startOfToday() }
        }
    }

    val upcoming = remember(episodes) {
        episodes.filter { it.airDateMillis >= startOfToday() }
            .sortedBy { it.airDateMillis }
            .take(8)
    }
    val recent = remember(episodes) {
        episodes.filter { it.airDateMillis < startOfToday() }
            .sortedByDescending { it.airDateMillis }
            .take(6)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .verticalScroll(rememberScrollState())
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        // ── Header ──────────────────────────────────────────────
        if (onBack != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onBack,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CyferCardSurface,
                        contentColor = CyferWhite,
                    ),
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "CALENDAR",
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = monthFormat.format(cursor),
                color = CyferWhite,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            val summary = when {
                loading -> "Loading your episode schedule…"
                episodes.isEmpty() && watchlist.isEmpty() -> "Add TV shows to My List to build your release calendar."
                episodes.isEmpty() -> "No upcoming episodes found for your saved shows."
                else -> {
                    val titles = episodes.distinctBy { "${it.mediaType}:${it.tmdbId}" }.size
                    "${episodes.size} episodes scheduled across $titles titles"
                }
            }
            Text(
                text = summary,
                color = CyferTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Filter pills + month nav ────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                CalendarFilter.values().forEach { f ->
                    app.cyfer.streaming.android.ui.common.CyferChip(
                        label = f.label,
                        selected = filter == f,
                        onClick = { filter = f },
                    )
                }
            }
            CalendarIconButton(onClick = { cursor = addMonths(cursor, -1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month", tint = CyferWhite, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                onClick = {
                    val today = Date()
                    cursor = monthStart(today)
                    selected = today
                },
                color = Color.Transparent,
                border = BorderStroke(1.dp, CyferCardSurfaceLight),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "Today",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            CalendarIconButton(onClick = { cursor = addMonths(cursor, 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month", tint = CyferWhite, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Month grid ──────────────────────────────────────────
        MonthGrid(
            cursor = cursor,
            selected = selected,
            eventsByDay = byDay,
            onPickDay = { selected = it },
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Selected day's episodes ─────────────────────────────
        val selectedFormat = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
        Text(
            text = selectedFormat.format(selected).uppercase(),
            color = CyferTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (selectedDayEpisodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "No episodes scheduled for this day.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                selectedDayEpisodes.forEach { ep ->
                    EpisodeRow(ep = ep, onClick = { onTitleClick(ep.tmdbId, ep.mediaType) })
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel("Upcoming")
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                upcoming.forEach { ep ->
                    EpisodeRow(ep = ep, onClick = { onTitleClick(ep.tmdbId, ep.mediaType) }, showDate = true)
                }
            }
        }

        if (recent.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel("Recently aired")
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                recent.forEach { ep ->
                    EpisodeRow(ep = ep, onClick = { onTitleClick(ep.tmdbId, ep.mediaType) }, showDate = true)
                }
            }
        }

        if (loading && episodes.isEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyferAccent)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = CyferTextTertiary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun CalendarIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CyferCardSurface,
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun MonthGrid(
    cursor: Date,
    selected: Date,
    eventsByDay: Map<String, List<CalendarEpisode>>,
    onPickDay: (Date) -> Unit,
) {
    val cal = remember(cursor) { Calendar.getInstance().apply { time = cursor } }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)

    // Mon-first weekday header (matches desktop convention).
    val dayNames = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    val cells = remember(cursor) { buildMonthCells(year, month) }
    val todayKey = remember { CalendarRepository.dayKey(System.currentTimeMillis()) }
    val selectedKey = remember(selected) { CalendarRepository.dayKey(selected.time) }

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            dayNames.forEach { name ->
                Text(
                    text = name,
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        cells.chunked(7).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { date ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            val dayKey = CalendarRepository.dayKey(date.time)
                            val hasEvents = eventsByDay[dayKey]?.isNotEmpty() == true
                            val isSelected = dayKey == selectedKey
                            val isToday = dayKey == todayKey
                            Surface(
                                onClick = { onPickDay(date) },
                                shape = RoundedCornerShape(10.dp),
                                color = when {
                                    isSelected -> CyferAccent
                                    isToday -> CyferCardSurface
                                    else -> Color.Transparent
                                },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = date.dayOfMonth().toString(),
                                            color = if (isSelected) CyferBlack else CyferWhite,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                                        )
                                        if (hasEvents) {
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSelected) CyferBlack else CyferAccent),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: CalendarEpisode, onClick: () -> Unit, showDate: Boolean = false) {
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    Surface(
        onClick = onClick,
        color = CyferCardSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyferDarkSurface),
            ) {
                val img = ep.stillUrl ?: ep.backdropUrl ?: ep.posterUrl
                if (!img.isNullOrBlank()) {
                    AsyncImage(
                        model = img,
                        contentDescription = ep.episodeName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Tv, contentDescription = null, tint = CyferTextTertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ep.seriesTitle,
                    color = CyferWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val episodeLabel = if (ep.mediaType == "anime")
                    "EP ${ep.episodeNumber.toString().padStart(2, '0')}  ·  ${ep.episodeName}"
                else
                    "S${ep.seasonNumber.toString().padStart(2, '0')} E${ep.episodeNumber.toString().padStart(2, '0')}  ·  ${ep.episodeName}"
                Text(
                    text = episodeLabel,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showDate) {
                    Text(
                        text = dateFmt.format(Date(ep.airDateMillis)),
                        color = CyferTextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Date helpers ───────────────────────────

private fun monthStart(date: Date): Date {
    val c = Calendar.getInstance()
    c.time = date
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.time
}

private fun addMonths(date: Date, delta: Int): Date {
    val c = Calendar.getInstance()
    c.time = date
    c.add(Calendar.MONTH, delta)
    return c.time
}

private fun startOfToday(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun Date.dayOfMonth(): Int {
    val c = Calendar.getInstance()
    c.time = this
    return c.get(Calendar.DAY_OF_MONTH)
}

/** Build Mon-first 7-col grid cells for the given month. Null = empty cell. */
private fun buildMonthCells(year: Int, month: Int): List<Date?> {
    val cal = Calendar.getInstance()
    cal.set(year, month, 1, 0, 0, 0); cal.set(Calendar.MILLISECOND, 0)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    // Convert Sunday=1..Saturday=7 to Mon-first 0..6.
    val firstDow = (firstDayOfWeek + 5) % 7
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = mutableListOf<Date?>()
    repeat(firstDow) { cells += null }
    for (day in 1..daysInMonth) {
        cal.set(year, month, day, 0, 0, 0); cal.set(Calendar.MILLISECOND, 0)
        cells += cal.time
    }
    while (cells.size % 7 != 0) cells += null
    return cells
}
