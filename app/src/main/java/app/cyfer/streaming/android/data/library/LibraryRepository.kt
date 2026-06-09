package app.cyfer.streaming.android.data.library

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private const val TAG = "LibraryRepo"
private const val PREFS_NAME = "cyfer_library"
private val LIBRARY_KEY = stringPreferencesKey("library_json")

private val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFS_NAME,
)

/**
 * Persists watchlist + progress as a single JSON blob in a Preferences
 * DataStore. Mirrors the way [SettingsRepository] stores AppSettings —
 * easier to reason about than separate keys, and matches the desktop's
 * `library.json` file conceptually.
 */
class LibraryRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val state: Flow<LibraryState> = context.libraryDataStore.data
        .catch { err ->
            Log.w(TAG, "Failed to read library, falling back to defaults", err)
            emit(emptyPreferences())
        }
        .map { prefs ->
            val raw = prefs[LIBRARY_KEY]
            if (raw.isNullOrBlank()) return@map LibraryState()
            runCatching { json.decodeFromString<LibraryState>(raw) }
                .getOrElse {
                    Log.w(TAG, "Library JSON decode failed; resetting", it)
                    LibraryState()
                }
        }

    val watchlist: Flow<List<WatchlistEntry>> = state.map { it.watchlist }
    val progress: Flow<List<ProgressEntry>> = state.map { it.progress }

    /** Atomic read-modify-write through DataStore.edit. */
    suspend fun update(transform: (LibraryState) -> LibraryState): LibraryState {
        var next = LibraryState()
        context.libraryDataStore.edit { prefs ->
            val current = prefs[LIBRARY_KEY]
                ?.let { runCatching { json.decodeFromString<LibraryState>(it) }.getOrNull() }
                ?: LibraryState()
            next = transform(current)
            prefs[LIBRARY_KEY] = json.encodeToString(LibraryState.serializer(), next)
        }
        return next
    }

    // ── Watchlist helpers ───────────────────────────────────────────

    suspend fun addToWatchlist(entry: WatchlistEntry): Boolean {
        var added = false
        update { current ->
            val existing = current.watchlist.firstOrNull {
                it.tmdbId == entry.tmdbId && it.mediaType == entry.mediaType
            }
            if (existing != null) return@update current
            added = true
            current.copy(watchlist = current.watchlist + entry)
        }
        return added
    }

    suspend fun removeFromWatchlist(tmdbId: Int, mediaType: String) {
        update { current ->
            current.copy(
                watchlist = current.watchlist.filterNot {
                    it.tmdbId == tmdbId && it.mediaType == mediaType
                },
            )
        }
    }

    suspend fun toggleWatchlist(entry: WatchlistEntry): Boolean {
        var nowInList = false
        update { current ->
            val exists = current.watchlist.any {
                it.tmdbId == entry.tmdbId && it.mediaType == entry.mediaType
            }
            nowInList = !exists
            current.copy(
                watchlist = if (exists) {
                    current.watchlist.filterNot {
                        it.tmdbId == entry.tmdbId && it.mediaType == entry.mediaType
                    }
                } else current.watchlist + entry,
            )
        }
        return nowInList
    }

    // ── Progress helpers ────────────────────────────────────────────

    /** Save or update a progress entry. Short positions are skipped. */
    suspend fun saveProgress(entry: ProgressEntry) {
        if (entry.position < ProgressEntry.MIN_SAVE_SECONDS) return
        update { current ->
            val without = current.progress.filterNot { it.key == entry.key }
            current.copy(progress = listOf(entry) + without)
        }
    }

    suspend fun removeProgress(tmdbId: Int, mediaType: String, season: Int? = null, episode: Int? = null) {
        val key = ProgressEntry(
            tmdbId = tmdbId, mediaType = mediaType, title = "",
            season = season, episode = episode, position = 0.0, duration = 0.0,
            updatedAt = 0,
        ).key
        update { current ->
            current.copy(progress = current.progress.filterNot { it.key == key })
        }
    }

    // ── Mark watched / unwatched ────────────────────────────────────
    //
    // "Watched" is modelled as a progress entry whose position == its
    // duration, so [ProgressEntry.watched] (the 85% rule) reports true.
    // We synthesise a duration if the real runtime isn't known yet —
    // any positive value works because position == duration => 100%.

    /**
     * Force a title/episode to "watched" by writing a full-duration
     * progress entry. [knownDurationSeconds] should be the real runtime
     * when available (so the Continue/History UIs show a sensible total),
     * otherwise a 1-second placeholder is used.
     */
    suspend fun markWatched(
        tmdbId: Int,
        mediaType: String,
        title: String,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        season: Int? = null,
        episode: Int? = null,
        seriesTmdbId: Int? = null,
        seriesTitle: String? = null,
        knownDurationSeconds: Double? = null,
    ) {
        val duration = (knownDurationSeconds ?: 1.0).coerceAtLeast(1.0)
        val entry = ProgressEntry(
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            season = season,
            episode = episode,
            seriesTmdbId = seriesTmdbId,
            seriesTitle = seriesTitle,
            position = duration,
            duration = duration,
            updatedAt = System.currentTimeMillis(),
        )
        update { current ->
            val without = current.progress.filterNot { it.key == entry.key }
            current.copy(progress = listOf(entry) + without)
        }
    }

    /** Clear the watched/in-progress state for a title or episode. */
    suspend fun markUnwatched(
        tmdbId: Int,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
    ) = removeProgress(tmdbId, mediaType, season, episode)

    /**
     * Mark a batch of episodes watched in a single atomic write — used
     * by "Mark season watched". Far cheaper than N separate edits.
     */
    suspend fun markEpisodesWatched(entries: List<ProgressEntry>) {
        if (entries.isEmpty()) return
        val stamped = entries.map { it.copy(updatedAt = System.currentTimeMillis()) }
        val keys = stamped.map { it.key }.toSet()
        update { current ->
            val without = current.progress.filterNot { it.key in keys }
            current.copy(progress = stamped + without)
        }
    }

    /** Clear watched state for a batch of episode keys (whole season). */
    suspend fun markEpisodesUnwatched(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val keySet = keys.toSet()
        update { current ->
            current.copy(progress = current.progress.filterNot { it.key in keySet })
        }
    }

    companion object {
        @Volatile private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext).also { instance = it }
            }
    }
}
