package app.cyfer.streaming.android.data.downloads

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

private const val TAG = "DownloadsRepo"
private const val PREFS_NAME = "cyfer_downloads"
private val DOWNLOADS_KEY = stringPreferencesKey("downloads_json")

private val Context.downloadsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFS_NAME,
)

/**
 * Persists every user-pinned download as a JSON blob, mirroring the
 * pattern used by [app.cyfer.streaming.android.data.library.LibraryRepository].
 *
 * One DataStore file per concern keeps decoding cheap and avoids cross-
 * concern contention when the user adds a new download mid-watch.
 */
class DownloadsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val state: Flow<DownloadsState> = context.downloadsDataStore.data
        .catch { err ->
            Log.w(TAG, "Failed to read downloads", err)
            emit(emptyPreferences())
        }
        .map { prefs ->
            val raw = prefs[DOWNLOADS_KEY]
            if (raw.isNullOrBlank()) return@map DownloadsState()
            runCatching { json.decodeFromString<DownloadsState>(raw) }
                .getOrElse {
                    Log.w(TAG, "Downloads JSON decode failed; resetting", it)
                    DownloadsState()
                }
        }

    val entries: Flow<List<DownloadEntry>> = state.map { it.entries }

    suspend fun update(transform: (DownloadsState) -> DownloadsState): DownloadsState {
        var next = DownloadsState()
        context.downloadsDataStore.edit { prefs ->
            val current = prefs[DOWNLOADS_KEY]
                ?.let { runCatching { json.decodeFromString<DownloadsState>(it) }.getOrNull() }
                ?: DownloadsState()
            next = transform(current)
            prefs[DOWNLOADS_KEY] = json.encodeToString(DownloadsState.serializer(), next)
        }
        return next
    }

    /** Pin a torrent for offline keep. Replaces any existing entry with
     *  the same info-hash so re-pin after delete works cleanly. */
    suspend fun pin(entry: DownloadEntry) {
        update { current ->
            val withoutDup = current.entries.filterNot { it.infoHash == entry.infoHash }
            current.copy(entries = withoutDup + entry)
        }
    }

    suspend fun unpin(infoHash: String) {
        update { current ->
            current.copy(entries = current.entries.filterNot { it.infoHash == infoHash })
        }
    }

    /** Refresh the byte / status fields of an existing entry. No-op if
     *  the entry isn't pinned. Called from the engine progress poller. */
    suspend fun updateProgress(infoHash: String, downloadedBytes: Long, sizeBytes: Long, status: DownloadStatus) {
        update { current ->
            val target = current.entries.firstOrNull { it.infoHash == infoHash } ?: return@update current
            val patched = target.copy(
                downloadedBytes = downloadedBytes,
                sizeBytes = sizeBytes.coerceAtLeast(target.sizeBytes),
                status = status,
            )
            current.copy(entries = current.entries.map { if (it.infoHash == infoHash) patched else it })
        }
    }

    suspend fun isPinned(infoHash: String): Boolean {
        var found = false
        update { current ->
            found = current.entries.any { it.infoHash == infoHash }
            current
        }
        return found
    }

    companion object {
        @Volatile private var instance: DownloadsRepository? = null
        fun get(context: Context): DownloadsRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadsRepository(context.applicationContext).also { instance = it }
            }
    }
}
