package app.cyfer.streaming.android.data.anilist

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cyfer.streaming.android.data.library.LibraryRepository
import app.cyfer.streaming.android.data.library.WatchlistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "AniListRepo"
private const val PREFS_NAME = "cyfer_anilist"
private val SESSION_KEY = stringPreferencesKey("anilist_session_json")
private val Context.aniListDataStore: DataStore<Preferences> by preferencesDataStore(PREFS_NAME)

/**
 * Minimal AniList client — implicit-OAuth token flow + GraphQL pull of
 * the user's PLANNING list, merged into the local watchlist.
 *
 * Phase 4 ships with manual token paste (works with any AniList app
 * registration). Future slices can add the in-app browser handoff once
 * we register a deep-link redirect URI.
 */
class AniListRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    @Serializable
    data class Session(
        val accessToken: String = "",
        val username: String? = null,
        val userId: Int? = null,
        val updatedAt: Long = 0L,
    ) {
        val isConnected: Boolean get() = accessToken.isNotBlank()
    }

    val session: Flow<Session> = context.aniListDataStore.data
        .catch { err -> Log.w(TAG, "AniList session read failed", err); emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[SESSION_KEY]
            if (raw.isNullOrBlank()) Session()
            else runCatching { json.decodeFromString<Session>(raw) }.getOrElse { Session() }
        }

    private suspend fun saveSession(s: Session) {
        context.aniListDataStore.edit { prefs ->
            prefs[SESSION_KEY] = json.encodeToString(Session.serializer(), s)
        }
    }

    suspend fun disconnect() = saveSession(Session())

    /**
     * Validate a token by querying `Viewer { id name }`. Persists on
     * success, returns false otherwise.
     */
    suspend fun setAccessToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        val viewerQuery = """{"query":"query { Viewer { id name } }"}""".toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(viewerQuery)
            .header("Authorization", "Bearer $token")
            .build()
        runCatching {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext false
                val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                val viewer = (obj["data"] as? JsonObject)?.get("Viewer") as? JsonObject ?: return@withContext false
                val id = (viewer["id"] as? JsonPrimitive)?.intOrNull
                val name = (viewer["name"] as? JsonPrimitive)?.contentOrNull
                saveSession(
                    Session(
                        accessToken = token,
                        username = name,
                        userId = id,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Import the user's "PLANNING" anime list into Cyfer's watchlist.
     * AniList anime carry idMal — we'd need to map MAL → TMDb for full
     * parity. For now we just use AniList's id as a synthetic tmdbId
     * with a +1_000_000_000 offset to avoid colliding with real TMDb
     * ids; the entry's title is the user-visible piece.
     */
    suspend fun importWatchlist(): SyncResult = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected || s.userId == null) return@withContext SyncResult(false, error = "Not connected")
        val library = LibraryRepository.get(context)
        var imported = 0; var skipped = 0
        runCatching {
            // GraphQL: pull every PLANNING entry from the user's anime list.
            val query = """{
              "query": "query (${'$'}userId: Int!) { MediaListCollection(userId: ${'$'}userId, type: ANIME, status: PLANNING) { lists { entries { media { id title { romaji english } seasonYear coverImage { large } averageScore } } } } }",
              "variables": {"userId": ${s.userId}}
            }""".trimIndent().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(query)
                .header("Authorization", "Bearer ${s.accessToken}")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext SyncResult(false, error = "AniList HTTP ${res.code}")
                val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                val lists = ((obj["data"] as? JsonObject)?.get("MediaListCollection") as? JsonObject)
                    ?.get("lists") as? JsonArray ?: return@withContext SyncResult(true)
                lists.forEach { list ->
                    val entries = (list as? JsonObject)?.get("entries") as? JsonArray ?: return@forEach
                    entries.forEach { e ->
                        val media = (e as? JsonObject)?.get("media") as? JsonObject ?: return@forEach
                        val id = (media["id"] as? JsonPrimitive)?.intOrNull ?: return@forEach
                        val titleObj = media["title"] as? JsonObject
                        val title = (titleObj?.get("english") as? JsonPrimitive)?.contentOrNull
                            ?: (titleObj?.get("romaji") as? JsonPrimitive)?.contentOrNull
                            ?: return@forEach
                        val year = (media["seasonYear"] as? JsonPrimitive)?.intOrNull?.toString()
                        val cover = ((media["coverImage"] as? JsonObject)?.get("large") as? JsonPrimitive)?.contentOrNull
                        val score = (media["averageScore"] as? JsonPrimitive)?.intOrNull?.let { it / 10f }
                        val added = library.addToWatchlist(
                            WatchlistEntry(
                                // Offset AniList ids so they don't collide with real TMDb ids.
                                tmdbId = id + 1_000_000_000,
                                mediaType = "anime",
                                title = title,
                                posterUrl = cover,
                                year = year,
                                voteAverage = score ?: 0f,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                        if (added) imported++ else skipped++
                    }
                }
            }
        }.onFailure { return@withContext SyncResult(false, error = it.message ?: it.toString()) }
        SyncResult(true, imported = imported, skipped = skipped)
    }

    data class SyncResult(val ok: Boolean, val imported: Int = 0, val skipped: Int = 0, val error: String? = null)

    /**
     * Push episode progress back to AniList.
     *   • aniListMediaId — the AniList numeric id of the anime
     *   • episodeNumber  — the most recently watched episode (1-based)
     *
     * Uses the SaveMediaListEntry mutation, which creates or updates
     * the list entry in one call. Silent on failure — never lets a
     * sync glitch interrupt playback.
     */
    suspend fun pushEpisodeProgress(aniListMediaId: Int, episodeNumber: Int) = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected) return@withContext
        if (aniListMediaId <= 0 || episodeNumber <= 0) return@withContext
        // GraphQL mutation. Marks status=CURRENT (watching) — AniList
        // auto-bumps to COMPLETED when progress hits episodeCount.
        val mutation = """{
          "query": "mutation (${'$'}mediaId: Int!, ${'$'}progress: Int!) { SaveMediaListEntry(mediaId: ${'$'}mediaId, status: CURRENT, progress: ${'$'}progress) { id progress } }",
          "variables": {"mediaId": $aniListMediaId, "progress": $episodeNumber}
        }""".trimIndent().toRequestBody(jsonMedia)
        runCatching {
            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(mutation)
                .header("Authorization", "Bearer ${s.accessToken}")
                .build()
            client.newCall(req).execute().use { /* swallow body */ }
        }.onFailure { Log.w(TAG, "AniList progress push failed: ${it.message}") }
    }

    companion object {
        @Volatile private var instance: AniListRepository? = null
        fun get(context: Context): AniListRepository = instance ?: synchronized(this) {
            instance ?: AniListRepository(context.applicationContext).also { instance = it }
        }
    }
}
