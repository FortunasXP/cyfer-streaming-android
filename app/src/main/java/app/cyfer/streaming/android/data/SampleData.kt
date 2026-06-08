package app.cyfer.streaming.android.data

import androidx.compose.ui.graphics.Color

data class FeaturedItem(
    val title: String,
    val year: Int,
    val rating: Float,
    val duration: String,
    val badges: List<String>,
    val category: String,
    val description: String,
    val gradientColors: List<Color>
)

data class ContinueWatchingItem(
    val title: String,
    val subtitle: String,
    val progress: Float,
    val accentColor: Color
)

data class TrendingItem(
    val title: String,
    val rating: Float,
    val accentColor: Color
)

data class MediaItem(
    val title: String,
    val year: Int? = null,
    val rating: Float? = null,
    val accentColor: Color = Color(0xFF2A2A2A)
)

// ── Featured hero ──────────────────────────────────────────────

val sampleFeatured = FeaturedItem(
    title = "Upgrade",
    year = 2018,
    rating = 7.5f,
    duration = "1h 40m",
    badges = listOf("4K", "DV", "ATMOS"),
    category = "FEATURED FILM",
    description = "A technophobe is implanted with a chip that gives him superhuman abilities.",
    gradientColors = listOf(
        Color(0xFFC4587A),
        Color(0xFF8B3A62),
        Color(0xFF2A1B3D),
        Color(0xFF050505)
    )
)

// ── Continue watching ──────────────────────────────────────────

val sampleContinueWatching = listOf(
    ContinueWatchingItem("Foundation", "S1 · E5", 0.65f, Color(0xFF1B4B6B)),
    ContinueWatchingItem("The Boys", "S4 · E2", 0.30f, Color(0xFF6B4B1B)),
    ContinueWatchingItem("Dune", "Resume 39m", 0.45f, Color(0xFF3B4B5B)),
    ContinueWatchingItem("Chernobyl", "S01 · E02", 0.80f, Color(0xFF4B3B2B))
)

// ── Trending ───────────────────────────────────────────────────

val sampleTrending = listOf(
    TrendingItem("Civil War", 8.2f, Color(0xFF1B6B6B)),
    TrendingItem("Challengers", 7.9f, Color(0xFF5B2B6B)),
    TrendingItem("Ripley", 8.6f, Color(0xFF6B4B1B)),
    TrendingItem("Shogun", 9.0f, Color(0xFF1B3B6B)),
    TrendingItem("3 Body Problem", 7.7f, Color(0xFF4B1B2B))
)

// ── Library content ────────────────────────────────────────────

val sampleMovies = listOf(
    MediaItem("Oppenheimer", 2023, 8.3f, Color(0xFF3B2B1B)),
    MediaItem("Poor Things", 2023, 8.0f, Color(0xFF1B4B3B)),
    MediaItem("Killers of the Flower Moon", 2023, 7.8f, Color(0xFF4B3B1B)),
    MediaItem("Past Lives", 2023, 8.1f, Color(0xFF2B3B5B)),
    MediaItem("The Zone of Interest", 2023, 7.4f, Color(0xFF3B3B3B))
)

val sampleShows = listOf(
    MediaItem("Fallout", 2024, 8.5f, Color(0xFF4B5B1B)),
    MediaItem("True Detective", 2024, 7.2f, Color(0xFF1B2B4B)),
    MediaItem("The Bear", 2023, 8.9f, Color(0xFF5B3B2B)),
    MediaItem("Slow Horses", 2024, 8.3f, Color(0xFF2B4B3B)),
    MediaItem("Severance", 2024, 8.7f, Color(0xFF3B2B4B))
)
