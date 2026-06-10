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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Refresh the access token when it's inside this pre-expiry window. */
private const val REFRESH_AHEAD_MS = 48 * 60 * 60 * 1000L

/**
 * Talks to Trakt directly. Handles device-code OAuth, persists the
 * token, refreshes it automatically (proactively near expiry and on
 * 401), and exposes scrobble / history / watchlist sync helpers.
 */
class TraktRepository(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Repository-owned scope for fire-and-forget pushes (scrobble stop
     *  from a dying composition, watchlist toggles). Survives any UI
     *  lifecycle — exactly what onDispose callers need. */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises token refreshes so concurrent API calls don't race
     *  the rotation (Trakt invalidates the old refresh token on use). */
    private val refreshMutex = Mutex()

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

    // ─────────────────────────── Token refresh ───────────────────────────

    /**
     * Session with a guaranteed-usable token. Refreshes proactively
     * inside the 48 h pre-expiry window; Trakt refresh tokens survive
     * access-token expiry, so even an install that sat idle past the
     * ~90-day token life recovers silently here. Returns null when not
     * connected or the refresh was rejected (token revoked).
     */
    private suspend fun validSession(): TraktSession? {
        val s = session.first()
        if (!s.isConnected) return null
        val threshold = System.currentTimeMillis() + REFRESH_AHEAD_MS
        if (s.expiresAt == 0L || s.expiresAt > threshold) return s
        return refreshMutex.withLock {
            // Another caller may have refreshed while we waited.
            val latest = session.first()
            when {
                !latest.isConnected -> null
                latest.expiresAt > threshold -> latest
                else -> doRefresh(latest) ?: latest.takeIf { !it.isExpired }
            }
        }
    }

    /**
     * Exchange the refresh token at `/oauth/token`. A 4xx means the
     * grant was revoked on trakt.tv — wipe the session so the Settings
     * UI offers reconnect instead of failing silently forever. Network
     * errors keep the old session (the access token may still work).
     */
    private suspend fun doRefresh(old: TraktSession): TraktSession? = withContext(Dispatchers.IO) {
        if (old.refreshToken.isBlank()) return@withContext null
        val body = """
            {"refresh_token":"${old.refreshToken}",
             "client_id":"${TraktConfig.CLIENT_ID}",
             "client_secret":"${TraktConfig.CLIENT_SECRET}",
             "redirect_uri":"urn:ietf:wg:oauth:2.0:oob",
             "grant_type":"refresh_token"}
        """.trimIndent().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("${TraktConfig.API_BASE}/oauth/token")
            .post(body)
            .header("User-Agent", TraktConfig.USER_AGENT)
            .build()
        runCatching {
            client.newCall(req).execute().use { res ->
                when {
                    res.isSuccessful -> {
                        val obj = json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                        val access = (obj["access_token"] as JsonPrimitive).content
                        val refresh = (obj["refresh_token"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: old.refreshToken
                        val createdAt = (obj["created_at"] as? JsonPrimitive)?.intOrNull
                            ?: (System.currentTimeMillis() / 1000).toInt()
                        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.intOrNull ?: 7776000
                        val fresh = old.copy(
                            accessToken = access,
                            refreshToken = refresh,
                            expiresAt = (createdAt.toLong() + expiresIn) * 1000L,
                            updatedAt = System.currentTimeMillis(),
                        )
                        saveSession(fresh)
                        Log.i(TAG, "Trakt token refreshed")
                        fresh
                    }
                    res.code in 400..403 -> {
                        Log.w(TAG, "Trakt refresh rejected (HTTP ${res.code}) — disconnecting")
                        saveSession(TraktSession())
                        null
                    }
                    else -> {
                        Log.w(TAG, "Trakt refresh failed (HTTP ${res.code})")
                        null
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Trakt refresh error: ${it.message}") }.getOrNull()
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
        if (imdbId.isBlank()) return@withContext
        val pct = progressPercent.coerceIn(0f, 100f)
        val body = if (season != null && episode != null) {
            """{"show":{"ids":{"imdb":"$imdbId"}},"episode":{"season":$season,"number":$episode},"progress":$pct}"""
        } else {
            """{"movie":{"ids":{"imdb":"$imdbId"}},"progress":$pct}"""
        }
        postTrakt("/scrobble/${action.token}", body, "scrobble/${action.token}")
    }

    /**
     * Fire-and-forget scrobble on the repository's own scope. The stop
     * scrobble fires from the player's onDispose, where the composition
     * scope is being cancelled at that very moment — launching there
     * silently dropped the one call that marks titles watched (≥80%).
     */
    fun scrobbleAsync(
        action: ScrobbleAction,
        imdbId: String,
        progressPercent: Float,
        season: Int? = null,
        episode: Int? = null,
    ) {
        repoScope.launch { scrobble(action, imdbId, progressPercent, season, episode) }
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
        if (imdbId.isBlank()) return@withContext
        val body = if (mediaType == "movie") {
            """{"movies":[{"ids":{"imdb":"$imdbId"},"watched_at":"$watchedAtIso"}]}"""
        } else {
            """{"shows":[{"ids":{"imdb":"$imdbId"},"seasons":[{"number":${season ?: 1},"episodes":[{"number":${episode ?: 1},"watched_at":"$watchedAtIso"}]}]}]}"""
        }
        postTrakt("/sync/history", body, "markWatched")
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
        if (imdbId.isBlank()) return@withContext
        val body = if (mediaType == "movie") {
            """{"movies":[{"ids":{"imdb":"$imdbId"}}]}"""
        } else {
            """{"shows":[{"ids":{"imdb":"$imdbId"},"seasons":[{"number":${season ?: 1},"episodes":[{"number":${episode ?: 1}}]}]}]}"""
        }
        postTrakt("/sync/history/remove", body, "removeWatched")
    }

    /** Mark a whole season watched in one request. */
    suspend fun markSeasonWatched(
        imdbId: String,
        season: Int,
        episodeNumbers: List<Int>,
        watchedAtIso: String = nowIso(),
    ) = withContext(Dispatchers.IO) {
        if (imdbId.isBlank() || episodeNumbers.isEmpty()) return@withContext
        val eps = episodeNumbers.joinToString(",") { """{"number":$it,"watched_at":"$watchedAtIso"}""" }
        val body = """{"shows":[{"ids":{"imdb":"$imdbId"},"seasons":[{"number":$season,"episodes":[$eps]}]}]}"""
        postTrakt("/sync/history", body, "markSeasonWatched")
    }

    // ─────────────────────────── Watchlist push ───────────────────────────

    /**
     * Mirror a local watchlist toggle to Trakt (`/sync/watchlist` and
     * `/sync/watchlist/remove`). Fire-and-forget like the scrobbles —
     * the local toggle must never wait on (or fail with) the network.
     */
    fun pushWatchlistAsync(add: Boolean, imdbId: String?, mediaType: String) {
        if (imdbId.isNullOrBlank()) return
        repoScope.launch {
            val key = if (mediaType == "movie") "movies" else "shows"
            val body = """{"$key":[{"ids":{"imdb":"$imdbId"}}]}"""
            postTrakt(
                if (add) "/sync/watchlist" else "/sync/watchlist/remove",
                body,
                "pushWatchlist",
            )
        }
    }

    /**
     * Authenticated Trakt write. Resolves a fresh session (refreshing
     * near expiry), and on a 401 — token invalidated server-side ahead
     * of schedule — forces one refresh and resends once. Non-2xx is
     * logged; callers never see errors (Trakt must not break the app).
     */
    private suspend fun postTrakt(path: String, jsonBody: String, tag: String) {
        val s = validSession() ?: return

        fun callOnce(token: String): Int? = runCatching {
            val req = Request.Builder()
                .url("${TraktConfig.API_BASE}$path")
                .post(jsonBody.toRequestBody(JSON_MEDIA))
                .header("Authorization", "Bearer $token")
                .header("trakt-api-version", "2")
                .header("trakt-api-key", TraktConfig.CLIENT_ID)
                .header("User-Agent", TraktConfig.USER_AGENT)
                .build()
            client.newCall(req).execute().use { it.code }
        }.onFailure { Log.w(TAG, "$tag failed: ${it.message}") }.getOrNull()

        var code = callOnce(s.accessToken) ?: return
        if (code == 401) {
            val refreshed = refreshMutex.withLock { doRefresh(session.first()) } ?: return
            code = callOnce(refreshed.accessToken) ?: return
        }
        if (code !in 200..299) Log.w(TAG, "$tag HTTP $code")
    }

    private fun nowIso(): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())

    suspend fun importWatchlist(): TraktSyncResult = withContext(Dispatchers.IO) {
        val s = validSession()
            ?: return@withContext TraktSyncResult(false, error = "Not connected to Trakt")
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
