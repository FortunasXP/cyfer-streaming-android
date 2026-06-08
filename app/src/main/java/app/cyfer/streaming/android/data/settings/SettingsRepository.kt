package app.cyfer.streaming.android.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private const val TAG = "SettingsRepo"
private const val PREFS_NAME = "cyfer_settings"
private val SETTINGS_KEY = stringPreferencesKey("settings_json")

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFS_NAME,
)

/**
 * Persists [AppSettings] as a single JSON blob in a Preferences DataStore.
 *
 * The whole-blob shape mirrors the desktop `settings.json` file so the
 * mental model stays the same across platforms — fields can be added
 * without migration boilerplate as long as defaults are sensible.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** Cold flow of the latest persisted settings; emits defaults on first read. */
    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { err ->
            Log.w(TAG, "Failed to read settings, falling back to defaults", err)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { prefs ->
            val raw = prefs[SETTINGS_KEY]
            if (raw.isNullOrBlank()) return@map AppSettings()
            val decoded = try {
                json.decodeFromString<AppSettings>(raw)
            } catch (err: Throwable) {
                Log.w(TAG, "Settings JSON decode failed; resetting to defaults", err)
                AppSettings()
            }
            // Always reconcile the provider list against the latest defaults so
            // providers added/removed in newer builds show up correctly.
            decoded.copy(
                torrentSourceProviders = normaliseTorrentSourceProviders(decoded.torrentSourceProviders),
            )
        }

    /**
     * Update the persisted settings via a transform on the current value.
     * Reads + writes atomically through DataStore.edit.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var next = AppSettings()
        context.settingsDataStore.edit { prefs ->
            val current = prefs[SETTINGS_KEY]
                ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
                ?: AppSettings()
            next = transform(current).let {
                it.copy(torrentSourceProviders = normaliseTorrentSourceProviders(it.torrentSourceProviders))
            }
            prefs[SETTINGS_KEY] = json.encodeToString(AppSettings.serializer(), next)
        }
        return next
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
