package app.cyfer.streaming.android.data.stremio

import android.util.Log
import app.cyfer.streaming.android.data.torrent.ResolvedStream
import app.cyfer.streaming.android.data.torrent.detectAnimeReleaseGroup
import app.cyfer.streaming.android.data.torrent.parseStreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Stremio Addon Protocol client. Spec:
 *   https://github.com/Stremio/stremio-addon-sdk/
 *
 *   GET /manifest.json                                 → manifest
 *   GET /stream/{type}/{id}.json                       → { streams: [...] }
 *   GET /stream/{type}/{id}:{season}:{episode}.json    (series)
 *
 * Mirrors `fetchAddonManifest` + `queryAddonStreams` in
 * `src/lib/api-bridge.ts` plus the URL/stream helpers from `src/lib/stremio.ts`.
 */

private const val TAG = "StremioClient"
private const val MANIFEST_TIMEOUT_MS = 15_000L
private const val STREAM_TIMEOUT_MS = 12_000L

private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// ═══════════════════════════════════════════════════════════════
//  URL helpers
// ═══════════════════════════════════════════════════════════════

/**
 * Strip a `stremio://` scheme, trailing `/manifest.json`, and trailing
 * slashes. Mirrors `normaliseAddonUrl` in `src/lib/stremio.ts`.
 */
fun normaliseAddonUrl(url: String): String {
    var clean = url.trim().trimEnd('/')
    if (clean.startsWith("stremio://", ignoreCase = true)) {
        clean = "https://${clean.substring("stremio://".length)}"
    }
    if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
        clean = if (Regex("^(localhost|127\\.0\\.0\\.1|\\[::1\\])", RegexOption.IGNORE_CASE).containsMatchIn(clean))
            "http://$clean" else "https://$clean"
    }
    if (clean.endsWith("/manifest.json", ignoreCase = true)) {
        clean = clean.substring(0, clean.length - "/manifest.json".length)
    }
    return clean
}

private fun isTorrentUrl(source: String?): Boolean {
    if (source.isNullOrBlank()) return false
    return Regex("^magnet:", RegexOption.IGNORE_CASE).containsMatchIn(source) ||
        Regex("\\.torrent(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(source)
}

private fun trackerParams(sources: List<String>?): String {
    if (sources.isNullOrEmpty()) return ""
    return sources.asSequence()
        .map { it.trim() }
        .map { if (it.startsWith("tracker:")) it.substring("tracker:".length) else it }
        .filter { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) || Regex("^udp://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        .joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
}

// ═══════════════════════════════════════════════════════════════
//  Manifest parsing + install
// ═══════════════════════════════════════════════════════════════

private fun stringList(el: kotlinx.serialization.json.JsonElement?): List<String> =
    (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun parseResources(arr: JsonArray?): List<StremioResource> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull?.let { StremioResource(name = it) }
            is JsonObject -> StremioResource(
                name = (el["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                types = stringList(el["types"]),
                idPrefixes = stringList(el["idPrefixes"]),
            )
            else -> null
        }
    }
}

private suspend fun fetchJson(url: String, timeoutMs: Long): String =
    withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            // Up to 3 attempts on 5xx / timeout / IO / empty body. Torrentio
            // and other addons on shared Cloudflare workers hit cold starts
            // often; a single retry wasn't enough — bumping to two retries
            // (400 ms then 1200 ms backoff) keeps the failure rate negligible
            // without adding much latency to the happy path.
            val backoffs = longArrayOf(400L, 1200L)
            var lastError: Throwable? = null
            for (attempt in 0..2) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json, text/plain, */*")
                        // Some Stremio addons sit behind Cloudflare and reject
                        // unknown user-agents (403/503). A browser UA gets through.
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 CyferStreaming/0.1",
                        )
                        .build()
                    val body = client.newCall(req).execute().use { res ->
                        val body = res.body?.string().orEmpty()
                        if (!res.isSuccessful) {
                            val snippet = body.take(160).replace(Regex("\\s+"), " ")
                            throw RuntimeException(
                                if (snippet.isBlank()) "HTTP ${res.code}"
                                else "HTTP ${res.code}: $snippet",
                            )
                        }
                        body
                    }
                    if (body.isBlank()) throw RuntimeException("Empty response")
                    return@withContext body
                } catch (err: Throwable) {
                    lastError = err
                    val msg = err.message.orEmpty()
                    val transient = msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("HTTP 5") ||
                        msg.contains("HTTP 408") ||
                        msg.contains("HTTP 429") ||
                        msg.contains("reset") ||
                        msg.contains("Empty response") ||
                        err is java.io.IOException
                    if (attempt < 2 && transient) {
                        kotlinx.coroutines.delay(backoffs[attempt])
                    } else {
                        throw err
                    }
                }
            }
            throw lastError ?: RuntimeException("Unknown failure")
        }
    }

/**
 * Fetch and parse an addon manifest. Returns an [InstalledAddon] populated
 * with just the fields we need. Throws on any failure with a message
 * suitable for surfacing in the UI.
 */
suspend fun fetchAndParseManifest(rawUrl: String): InstalledAddon {
    val transportUrl = normaliseAddonUrl(rawUrl)
    val manifestUrl = "$transportUrl/manifest.json"
    val text = runCatching { fetchJson(manifestUrl, MANIFEST_TIMEOUT_MS) }
        .getOrElse { throw RuntimeException("Failed to fetch manifest: ${it.message ?: it.toString()}") }
    val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }
        .getOrNull() ?: throw RuntimeException("Manifest is not valid JSON")

    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
        ?: throw RuntimeException("Manifest missing 'id'")
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
        ?: throw RuntimeException("Manifest missing 'name'")

    return InstalledAddon(
        transportUrl = transportUrl,
        id = id,
        name = name,
        description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        version = (obj["version"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        logo = (obj["logo"] as? JsonPrimitive)?.contentOrNull,
        types = stringList(obj["types"]),
        resources = parseResources(obj["resources"] as? JsonArray),
        idPrefixes = stringList(obj["idPrefixes"]),
        enabled = true,
    )
}

// ═══════════════════════════════════════════════════════════════
//  Stream querying
// ═══════════════════════════════════════════════════════════════

/**
 * Map our app-level media type to the Stremio type tokens the addon
 * understands. Returns ordered preferences — first match wins when
 * scanning the manifest's declared types.
 */
private fun targetTypesFor(mediaType: String, isEpisode: Boolean): List<String> = when (mediaType) {
    "tv" -> listOf("series", "tv")
    "anime" -> if (isEpisode) listOf("anime", "series", "tv") else listOf("anime", "movie", "series", "tv")
    else -> listOf("movie")
}

/**
 * Build the `/stream/<type>/<id>[:S:E].json` path for the given addon, or
 * null if the addon doesn't declare a stream resource matching the type.
 * Mirrors `buildStreamPath` in `src/lib/stremio.ts`.
 */
private fun buildStreamPath(
    addon: InstalledAddon,
    stremioId: String,
    mediaType: String,
    season: Int?,
    episode: Int?,
): String? {
    val isEpisode = season != null && episode != null
    val targetTypes = targetTypesFor(mediaType, isEpisode)
    val manifestTypes = addon.types
    val manifestTargetType = targetTypes.firstOrNull { it in manifestTypes }
    var targetType = manifestTargetType ?: targetTypes.first()

    val streamRes = addon.resources.firstOrNull { r ->
        if (r.name != "stream") return@firstOrNull false
        val types = r.types
        if (types.isEmpty()) return@firstOrNull manifestTypes.isEmpty() || manifestTargetType != null
        val matched = targetTypes.firstOrNull { it in types }
        if (matched != null) targetType = matched
        matched != null
    } ?: return null

    return if ((mediaType == "tv" || mediaType == "anime") && isEpisode)
        "/stream/$targetType/$stremioId:$season:$episode.json"
    else
        "/stream/$targetType/$stremioId.json"
}

private fun resolvePlayableUrl(stream: JsonObject): String? {
    val url = (stream["url"] as? JsonPrimitive)?.contentOrNull
    if (!url.isNullOrBlank()) return url
    val infoHash = (stream["infoHash"] as? JsonPrimitive)?.contentOrNull
    if (!infoHash.isNullOrBlank()) {
        val name = URLEncoder.encode(
            (stream["title"] as? JsonPrimitive)?.contentOrNull
                ?: (stream["name"] as? JsonPrimitive)?.contentOrNull
                ?: infoHash,
            "UTF-8",
        )
        val sources = (stream["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return "magnet:?xt=urn:btih:$infoHash&dn=$name${trackerParams(sources)}"
    }
    val external = (stream["externalUrl"] as? JsonPrimitive)?.contentOrNull
    if (isTorrentUrl(external)) return external
    return null
}

private val SIZE_TEXT_RE = Regex(
    "(\\d+(?:[.,]\\d+)?)\\s*(GiB|GB|MiB|MB|TiB|TB|KiB|KB)\\b",
    RegexOption.IGNORE_CASE,
)

private fun streamSize(stream: JsonObject): String? {
    // 1) Modern Stremio addons set behaviorHints.videoSize — fast path.
    val bh = stream["behaviorHints"] as? JsonObject
    val bytes = (bh?.get("videoSize") as? JsonPrimitive)?.longOrNull
    if (bytes != null && bytes > 0) {
        return when {
            bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.0f MiB".format(bytes / (1024.0 * 1024))
            else -> null
        }
    }
    // 2) Torrentio embeds size inline in title / description text, like
    //    "💾 1.41 GB" or "📺 1080p 💾 4.50 GiB". Match the first occurrence.
    val haystack = listOfNotNull(
        (stream["title"] as? JsonPrimitive)?.contentOrNull,
        (stream["description"] as? JsonPrimitive)?.contentOrNull,
        (stream["name"] as? JsonPrimitive)?.contentOrNull,
    ).joinToString(" ")
    val match = SIZE_TEXT_RE.find(haystack) ?: return null
    val value = match.groupValues[1].replace(',', '.')
    val unit = match.groupValues[2].uppercase()
    // Normalise the binary / decimal labels the way the rest of Cyfer
    // displays them (binary feels more accurate for torrent contents).
    val displayUnit = when (unit) {
        "KB", "KIB" -> "KiB"
        "MB", "MIB" -> "MiB"
        "GB", "GIB" -> "GiB"
        "TB", "TIB" -> "TiB"
        else -> unit
    }
    return "$value $displayUnit"
}

/** Outcome of a single addon query — carries either streams or a UI-friendly error. */
data class AddonStreamOutcome(
    val addon: InstalledAddon,
    val streams: List<ResolvedStream>,
    val error: String? = null,
)

private suspend fun queryAddonStreams(
    addon: InstalledAddon,
    stremioId: String,
    mediaType: String,
    season: Int?,
    episode: Int?,
): AddonStreamOutcome {
    val path = buildStreamPath(addon, stremioId, mediaType, season, episode)
        ?: return AddonStreamOutcome(addon, emptyList(), error = "Addon does not declare a stream resource for $mediaType")
    val url = "${addon.transportUrl}$path"
    Log.d(TAG, "→ GET $url")
    val text = try {
        fetchJson(url, STREAM_TIMEOUT_MS)
    } catch (err: Throwable) {
        Log.w(TAG, "✗ ${addon.name}: ${err.message}  (url=$url)")
        return AddonStreamOutcome(addon, emptyList(), error = err.message ?: err.toString())
    }
    Log.d(TAG, "← ${addon.name}: ${text.length} bytes")
    val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return AddonStreamOutcome(addon, emptyList(), error = "Invalid JSON response")
    val arr = (obj["streams"] as? JsonArray) ?: return AddonStreamOutcome(addon, emptyList())
    val streams = arr.mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        val playable = resolvePlayableUrl(s) ?: return@mapNotNull null
        val title = (s["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val nameField = (s["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val description = (s["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val qualityText = listOf(title, nameField, description).filter { it.isNotEmpty() }.joinToString(" ")
        val bh = s["behaviorHints"] as? JsonObject
        val filename = (bh?.get("filename") as? JsonPrimitive)?.contentOrNull
        val releaseGroup = detectAnimeReleaseGroup(listOfNotNull(filename, title, nameField, description, addon.name).joinToString(" "))
        val infoHash = (s["infoHash"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        val trackers = (s["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        ResolvedStream(
            addonName = addon.name,
            addonId = addon.id,
            playableUrl = playable,
            quality = parseStreamQuality(qualityText),
            releaseGroup = releaseGroup,
            title = title.ifEmpty { nameField.ifEmpty { addon.name } },
            size = streamSize(s),
            seeders = null,
            leechers = null,
            infoHash = infoHash,
            trackers = trackers,
        )
    }
    return AddonStreamOutcome(addon, streams)
}

/**
 * Fan out to every enabled addon, return the union of [ResolvedStream]s.
 * Failures are logged and skipped — partial results are better than none.
 *
 * `stremioId` is the addon-facing identifier; for movies/TV that's the
 * IMDb id (`tt12345678`). Callers should resolve it from TMDb first.
 */
data class AddonSearchResult(
    val streams: List<ResolvedStream>,
    val outcomes: List<AddonStreamOutcome>,
) {
    val errors: List<AddonStreamOutcome> get() = outcomes.filter { it.error != null }
}

// ═══════════════════════════════════════════════════════════════
//  Subtitles
// ═══════════════════════════════════════════════════════════════

data class ResolvedAddonSubtitle(
    val addonName: String,
    val addonId: String,
    val url: String,
    val lang: String,
    val label: String,
    val sourceId: String?,
)

private fun buildSubtitlePath(addon: InstalledAddon, stremioId: String, mediaType: String, season: Int?, episode: Int?): String? {
    // Only addons with a `subtitles` resource serve this endpoint.
    val hasSubsResource = addon.resources.any { it.name == "subtitles" }
    if (!hasSubsResource) return null
    val isEpisode = season != null && episode != null
    val type = when (mediaType) {
        "tv" -> "series"
        "anime" -> if (isEpisode) "series" else "anime"
        else -> "movie"
    }
    return if (isEpisode && (mediaType == "tv" || mediaType == "anime"))
        "/subtitles/$type/$stremioId:$season:$episode.json"
    else
        "/subtitles/$type/$stremioId.json"
}

/**
 * Query every enabled addon for subtitle tracks. Mirrors the desktop's
 * `bridge.getSubtitlesForTitle`. Addons without a `subtitles` resource
 * are skipped silently.
 */
suspend fun getAddonSubtitles(
    addons: List<InstalledAddon>,
    stremioId: String,
    mediaType: String,
    season: Int? = null,
    episode: Int? = null,
): List<ResolvedAddonSubtitle> {
    val enabled = addons.filter { it.enabled && stremioId.isNotBlank() }
    if (enabled.isEmpty()) return emptyList()
    return coroutineScope {
        val jobs = enabled.mapNotNull { addon ->
            val path = buildSubtitlePath(addon, stremioId, mediaType, season, episode) ?: return@mapNotNull null
            async {
                runCatching {
                    val text = fetchJson("${addon.transportUrl}$path", STREAM_TIMEOUT_MS)
                    val obj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching emptyList<ResolvedAddonSubtitle>()
                    val arr = (obj["subtitles"] as? JsonArray) ?: return@runCatching emptyList()
                    arr.mapNotNull { el ->
                        val s = el as? JsonObject ?: return@mapNotNull null
                        val url = (s["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val lang = (s["lang"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: "und"
                        val id = (s["id"] as? JsonPrimitive)?.contentOrNull
                        val label = (s["SubFileName"] as? JsonPrimitive)?.contentOrNull
                            ?: (s["title"] as? JsonPrimitive)?.contentOrNull
                            ?: addon.name
                        ResolvedAddonSubtitle(
                            addonName = addon.name,
                            addonId = addon.id,
                            url = url,
                            lang = lang,
                            label = label,
                            sourceId = id,
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }
        jobs.awaitAll().flatten()
    }
}

suspend fun getAddonStreams(
    addons: List<InstalledAddon>,
    stremioId: String,
    mediaType: String,
    season: Int? = null,
    episode: Int? = null,
): AddonSearchResult {
    val enabled = addons.filter { it.enabled && stremioId.isNotBlank() }
    Log.i(
        TAG,
        "getAddonStreams: stremioId=$stremioId type=$mediaType " +
            "S${season ?: "-"}E${episode ?: "-"} enabled=${enabled.size}/${addons.size}",
    )
    if (enabled.isEmpty()) return AddonSearchResult(emptyList(), emptyList())
    val outcomes = coroutineScope {
        enabled.map { addon ->
            async {
                val outcome = queryAddonStreams(addon, stremioId, mediaType, season, episode)
                Log.i(
                    TAG,
                    "  · ${addon.name}: ${outcome.streams.size} streams" +
                        (outcome.error?.let { " (error: $it)" } ?: ""),
                )
                outcome
            }
        }.awaitAll()
    }
    return AddonSearchResult(
        streams = outcomes.flatMap { it.streams },
        outcomes = outcomes,
    )
}
