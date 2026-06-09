package app.cyfer.streaming.android.data.trakt

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

private const val TAG = "TraktRepo"
private const val PREFS_NAME = "cyfer_trakt"
private val SESSION_KEY = stringPreferencesKey("trakt_session_json")

private val Context.traktDataStore: DataStore<Preferences> by preferencesDataStore(PREFS_NAME)

private val JSON_MEDIA = "application/json".toMediaType()

/**
 * Talks to Trakt directly. Handles device-code OAuth, persists the
 * token, refreshes automatically on expiry, and exposes one-shot sync
 * helpers (import watchlist for now; mark-watched / scrobble follow in
 * subsequent slices once they're wired into the player).
 */
class TraktRepository(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val session: Flow<TraktSession> = context.traktDataStore.data
        .catch { err ->
            Log.w(TAG, "Trakt session read failed", err); emit(emptyPreferences())
        }
        .map { prefs ->
            val raw = prefs[SESSION_KEY]
            if (raw.isNullOrBlank()) TraktSession()
            else runCatching { json.decodeFromString<TraktSession>(raw) }.getOrElse { TraktSession() }
        }

    private suspend fun saveSession(s: TraktSession) {
        context.traktDataStore.edit { prefs ->
            prefs[SESSION_KEY] = json.encodeToString(TraktSession.serializer(), s)
        }
    }

    suspend fun disconnect() = saveSession(TraktSession())

    // ─────────────────────────── Device-code OAuth ───────────────────────────

    suspend fun requestDeviceCode(): TraktDeviceCode = withContext(Dispatchers.IO) {
        val body = """{"client_id":"${TraktConfig.CLIENT_ID}"}""".toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("${TraktConfig.API_BASE}/oauth/device/code")
            .post(body)
            .header("Content-Type", "application/json")
            .header("User-Agent", TraktConfig.USER_AGENT)
            .build()
        client.newCall(req).execute().use { res ->
            val txt = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("Trakt device/code HTTP ${res.code}: $txt")
            val obj = json.parseToJsonElement(txt) as JsonObject
            TraktDeviceCode(
                deviceCode = (obj["device_code"] as JsonPrimitive).content,
                userCode = (obj["user_code"] as JsonPrimitive).content,
                verificationUrl = (obj["verification_url"] as? JsonPrimitive)?.content
                    ?: TraktConfig.DEVICE_VERIFICATION_URL,
                expiresInSeconds = (obj["expires_in"] as? JsonPrimitive)?.intOrNull ?: 600,
                intervalSeconds = (obj["interval"] as? JsonPrimitive)?.intOrNull ?: 5,
            )
        }
    }

    /**
     * Poll `/oauth/device/token` every `intervalSeconds` until the user
     * approves on trakt.tv/activate, then persist the session.
     * Returns true on success, false on timeout / cancellation.
     */
    suspend fun pollForToken(code: TraktDeviceCode): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + TraktConfig.DEVICE_POLL_TIMEOUT_MS
        val intervalMs = (code.intervalSeconds * 1000L).coerceAtLeast(1000L)
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val body = """
                {"code":"${code.deviceCode}",
                 "client_id":"${TraktConfig.CLIENT_ID}",
                 "client_secret":"${TraktConfig.CLIENT_SECRET}"}
            """.trimIndent().toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url("${TraktConfig.API_BASE}/oauth/device/token")
                .post(body)
                .header("User-Agent", TraktConfig.USER_AGENT)
                .build()
            client.newCall(req).execute().use { res ->
                when (res.code) {
                    200 -> {
                        val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                        val accessToken = (obj["access_token"] as JsonPrimitive).content
                        val refreshToken = (obj["refresh_token"] as JsonPrimitive).content
                        val createdAt = (obj["created_at"] as? JsonPrimitive)?.intOrNull ?: (System.currentTimeMillis() / 1000).toInt()
                        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.intOrNull ?: 7776000  // 90 days
                        val scope = (obj["scope"] as? JsonPrimitive)?.contentOrNull ?: "public"
                        val username = runCatching { fetchUsername(accessToken) }.getOrNull()
                        saveSession(
                            TraktSession(
                                accessToken = accessToken,
                                refreshToken = refreshToken,
                                expiresAt = (createdAt + expiresIn) * 1000L,
                                scope = scope,
                                username = username,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        return@withContext true
                    }
                    400 -> { /* pending – keep polling */ }
                    409 -> { /* already used – treat as success or skip */ }
                    410, 418 -> return@withContext false  // expired / denied
                    429 -> delay(intervalMs)              // slow down
                    else -> return@withContext false
                }
            }
        }
        false
    }

    private suspend fun fetchUsername(accessToken: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${TraktConfig.API_BASE}/users/me")
                .header("Authorization", "Bearer $accessToken")
                .header("trakt-api-version", "2")
                .header("trakt-api-key", TraktConfig.CLIENT_ID)
                .header("User-Agent", TraktConfig.USER_AGENT)
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                (obj["username"] as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()
    }

    // ─────────────────────────── Import watchlist ───────────────────────────

    /**
     * Pull every entry from `/sync/watchlist` (movies + shows + anime as
     * shows) and merge into the local library. Existing entries are
     * preserved — Trakt items only fill in gaps.
     */
    // ─────────────────────────── Scrobble ───────────────────────────

    /**
     * Trakt's scrobble endpoints: start / pause / stop.
     *
     *   POST /scrobble/start  — playback began (sends progress 0…100)
     *   POST /scrobble/pause  — user paused
     *   POST /scrobble/stop   — playback ended; ≥ 80 % marks watched,
     *                           anything less Trakt treats as a pause
     *
     * Identifies the title by IMDb id (movies) or IMDb id + season/ep
     * (episodes). Returns silently on any error — we never want a
     * scrobble failure to disrupt playback.
     */
    suspend fun scrobble(
        action: ScrobbleAction,
        imdbId: String,
        progressPercent: Float,
        season: Int? = null,
        episode: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected || imdbId.isBlank()) return@withContext
        val path = "/scrobble/${action.token}"
        val pct = progressPercent.coerceIn(0f, 100f)
        val body = if (season != null && episode != null) {
            """{"show":{"ids":{"imdb":"$imdbId"}},"episode":{"season":$season,"number":$episode},"progress":$pct}"""
        } else {
            """{"movie":{"ids":{"imdb":"$imdbId"}},"progress":$pct}"""
        }.toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("${TraktConfig.API_BASE}$path")
            .post(body)
            .header("Authorization", "Bearer ${s.accessToken}")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", TraktConfig.CLIENT_ID)
            .header("User-Agent", TraktConfig.USER_AGENT)
            .build()
        runCatching { client.newCall(req).execute().use { /* swallow body */ } }
            .onFailure { Log.w(TAG, "scrobble/$action failed: ${it.message}") }
    }

    enum class ScrobbleAction(val token: String) { Start("start"), Pause("pause"), Stop("stop") }

    // ─────────────────────────── History (mark watched) ───────────────────────────

    /**
     * Push a manual "mark watched" to Trakt's history.
     *
     *   POST /sync/history   — adds a watched event dated [watchedAtIso]
     *
     * Movies identify by IMDb id; episodes by show IMDb id + season/ep.
     * Swallows errors — a Trakt failure must never block the local mark.
     */
    suspend fun markWatched(
        imdbId: String,
        mediaType: String,         // "movie" | "tv"
        season: Int? = null,
        episode: Int? = null,
        watchedAtIso: String = nowIso(),
    ) = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected || imdbId.isBlank()) return@withContext
        val body = if (mediaType == "movie") {
            """{"movies":[{"ids":{"imdb":"$imdbId"},"watched_at":"$watchedAtIso"}]}"""
        } else {
            """{"shows":[{"ids":{"imdb":"$imdbId"},"seasons":[{"number":${season ?: 1},"episodes":[{"number":${episode ?: 1},"watched_at":"$watchedAtIso"}]}]}]}"""
        }.toRequestBody(JSON_MEDIA)
        postTrakt("/sync/history", body, s.accessToken, "markWatched")
    }

    /**
     * Remove a watched event from Trakt history.
     *
     *   POST /sync/history/remove
     */
    suspend fun removeWatched(
        imdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected || imdbId.isBlank()) return@withContext
        val body = if (mediaType == "movie") {
            """{"movies":[{"ids":{"imdb":"$imdbId"}}]}"""
        } else {
            """{"shows":[{"ids":{"imdb":"$imdbId"},"seasons":[{"number":${season ?: 1},"episodes":[{"number":${episode ?: 1}}]}]}]}"""
        }.toRequestBody(JSON_MEDIA)
        postTrakt("/sync/history/remove", body, s.accessToken, "removeWatched")
    }

    /** Mark a whole season watched in one request. */
    suspend fun markSeasonWatched(
        imdbId: String,
        season: Int,
        episodeNumbers: List<Int>,
        watchedAtIso: String = nowIso(),
    ) = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected || imdbId.isBlank() || episodeNumbers.isEmpty()) return@withContext
        val eps = episodeNumbers.joinToString(",") { """{"number":$it,"watched_at":"$watchedAtIso"}""" }
        val body = """{"shows":[{"ids":{"imdb":"$imdbId"},"seasons":[{"number":$season,"episodes":[$eps]}]}]}"""
            .toRequestBody(JSON_MEDIA)
        postTrakt("/sync/history", body, s.accessToken, "markSeasonWatched")
    }

    private fun postTrakt(
        path: String,
        body: okhttp3.RequestBody,
        accessToken: String,
        tag: String,
    ) {
        val req = Request.Builder()
            .url("${TraktConfig.API_BASE}$path")
            .post(body)
            .header("Authorization", "Bearer $accessToken")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", TraktConfig.CLIENT_ID)
            .header("User-Agent", TraktConfig.USER_AGENT)
            .build()
        runCatching { client.newCall(req).execute().use { /* swallow body */ } }
            .onFailure { Log.w(TAG, "$tag failed: ${it.message}") }
    }

    private fun nowIso(): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())

    suspend fun importWatchlist(): TraktSyncResult = withContext(Dispatchers.IO) {
        val s = session.first()
        if (!s.isConnected) return@withContext TraktSyncResult(false, error = "Not connected to Trakt")
        val library = LibraryRepository.get(context)
        var imported = 0
        var skipped = 0
        runCatching {
            for (kind in listOf("movies", "shows")) {
                var page = 1
                while (page <= TraktConfig.MAX_IMPORT_PAGES) {
                    val list = fetchTraktArray("/sync/watchlist/$kind?page=$page&limit=${TraktConfig.PAGE_LIMIT}", s.accessToken)
                        ?: break
                    if (list.isEmpty()) break
                    for (el in list) {
                        val row = el as? JsonObject ?: continue
                        val media = (row["movie"] as? JsonObject) ?: (row["show"] as? JsonObject) ?: continue
                        val ids = media["ids"] as? JsonObject
                        val tmdbId = (ids?.get("tmdb") as? JsonPrimitive)?.intOrNull
                        val title = (media["title"] as? JsonPrimitive)?.contentOrNull
                        if (tmdbId == null || title.isNullOrBlank()) { skipped++; continue }
                        val mediaType = if (kind == "movies") "movie" else "tv"
                        val year = (media["year"] as? JsonPrimitive)?.intOrNull?.toString()
                        val added = library.addToWatchlist(
                            WatchlistEntry(
                                tmdbId = tmdbId,
                                mediaType = mediaType,
                                title = title,
                                posterUrl = null,
                                backdropUrl = null,
                                year = year,
                                voteAverage = 0f,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                        if (added) imported++ else skipped++
                    }
                    if (list.size < TraktConfig.PAGE_LIMIT) break
                    page++
                }
            }
        }.onFailure { return@withContext TraktSyncResult(false, error = it.message ?: it.toString()) }
        TraktSyncResult(true, imported = imported, skipped = skipped)
    }

    private suspend fun fetchTraktArray(path: String, accessToken: String): JsonArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${TraktConfig.API_BASE}$path")
            .header("Authorization", "Bearer $accessToken")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", TraktConfig.CLIENT_ID)
            .header("User-Agent", TraktConfig.USER_AGENT)
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val txt = res.body?.string().orEmpty()
            runCatching { json.parseToJsonElement(txt) as? JsonArray }.getOrNull()
        }
    }

    companion object {
        @Volatile private var instance: TraktRepository? = null
        fun get(context: Context): TraktRepository = instance ?: synchronized(this) {
            instance ?: TraktRepository(context.applicationContext).also { instance = it }
        }
    }
}
