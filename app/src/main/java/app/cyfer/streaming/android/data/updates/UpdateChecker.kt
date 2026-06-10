package app.cyfer.streaming.android.data.updates

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Side-load update check against the GitHub releases feed (desktop
 * parity with its "Check automatically" updater, scaled down to what
 * makes sense for an APK: detect a newer tag, link to the release
 * page). Checked lazily when Settings opens, cached per process —
 * unauthenticated GitHub API allows 60 req/h, one per app session is
 * plenty.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/FortunasXP/cyfer-streaming-android/releases/latest"

    data class UpdateInfo(
        /** Human version of the newer release, e.g. "0.3.0". */
        val versionName: String,
        /** Release page with the APK assets. */
        val releaseUrl: String,
    )

    @Serializable
    private data class GithubRelease(
        val tag_name: String? = null,
        val html_url: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Process-lifetime cache: null = not checked yet; Result wraps
     *  "checked, found nothing" (success(null)) vs network failure. */
    @Volatile private var cached: Result<UpdateInfo?>? = null

    /**
     * Returns the newer release if one exists, null when current is up
     * to date or the check failed (a failed check is cached too — we
     * don't hammer the API on every Settings open while offline).
     */
    suspend fun checkForNewerRelease(currentVersion: String): UpdateInfo? {
        cached?.let { return it.getOrNull() }
        val result = runCatching { fetchLatest(currentVersion) }
            .onFailure { Log.w(TAG, "Update check failed: ${it.message}") }
        cached = result
        return result.getOrNull()
    }

    private suspend fun fetchLatest(currentVersion: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val release = json.decodeFromString<GithubRelease>(resp.body?.string().orEmpty())
                if (release.draft || release.prerelease) return@withContext null
                val tag = release.tag_name?.trim().orEmpty()
                val url = release.html_url?.trim().orEmpty()
                if (tag.isEmpty() || url.isEmpty()) return@withContext null
                if (!isNewer(tag, currentVersion)) return@withContext null
                UpdateInfo(versionName = tag.removePrefix("v"), releaseUrl = url)
            }
        }

    /** Numeric dotted compare, tolerant of "v" prefixes and uneven
     *  lengths: v0.3.0 > 0.2.0, 0.2.10 > 0.2.9, 1.0 == 1.0.0. */
    private fun isNewer(candidate: String, current: String): Boolean {
        fun parse(v: String): List<Int> = v.removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .split(".")
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
