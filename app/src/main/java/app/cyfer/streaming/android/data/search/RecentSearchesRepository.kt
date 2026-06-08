package app.cyfer.streaming.android.data.search

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

private const val TAG = "RecentSearches"
private const val PREFS_NAME = "cyfer_recent_searches"
private val RECENT_KEY = stringPreferencesKey("recent_csv")

private val Context.recentSearchesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFS_NAME,
)

/**
 * Persists the user's recent search queries (most-recent first, max 12).
 * Stored as a `\n`-separated string in a Preferences DataStore so we don't
 * need a JSON model for what is effectively `List<String>`.
 */
class RecentSearchesRepository(private val context: Context) {

    val recent: Flow<List<String>> = context.recentSearchesDataStore.data
        .catch { err ->
            Log.w(TAG, "Failed to read recent searches", err)
            emit(emptyPreferences())
        }
        .map { prefs ->
            prefs[RECENT_KEY]
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }

    suspend fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.recentSearchesDataStore.edit { prefs ->
            val current = prefs[RECENT_KEY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            val without = current.filterNot { it.equals(trimmed, ignoreCase = true) }
            val next = (listOf(trimmed) + without).take(MAX_RECENT)
            prefs[RECENT_KEY] = next.joinToString("\n")
        }
    }

    suspend fun remove(query: String) {
        context.recentSearchesDataStore.edit { prefs ->
            val current = prefs[RECENT_KEY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            val next = current.filterNot { it.equals(query, ignoreCase = true) }
            prefs[RECENT_KEY] = next.joinToString("\n")
        }
    }

    suspend fun clear() {
        context.recentSearchesDataStore.edit { prefs ->
            prefs.remove(RECENT_KEY)
        }
    }

    companion object {
        private const val MAX_RECENT = 12

        @Volatile private var instance: RecentSearchesRepository? = null

        fun get(context: Context): RecentSearchesRepository =
            instance ?: synchronized(this) {
                instance ?: RecentSearchesRepository(context.applicationContext).also { instance = it }
            }
    }
}
