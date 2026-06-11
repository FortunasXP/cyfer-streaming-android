package app.cyfer.streaming.android.data.torrent

import android.util.Log
import app.cyfer.streaming.android.data.settings.ANIME_ONLY_PROVIDERS
import app.cyfer.streaming.android.data.settings.DEFAULT_TORRENT_SOURCE_PROVIDERS
import app.cyfer.streaming.android.data.settings.TorrentSourceProviderId
import app.cyfer.streaming.android.data.settings.TorrentSourceProviderSettings
import app.cyfer.streaming.android.data.settings.normaliseTorrentSourceProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cyfer Android port of `src/lib/torrent-source-search.ts`.
 *
 * Mirrors the desktop pipeline: a single [searchCyferTorrentSources] entry
 * fans out to every enabled [TorrentSourceProviderSettings], dedupes,
 * scores, and returns both raw [TorrentSourceResult]s and player-ready
 * [ResolvedStream]s.
 */

private const val TAG = "TorrentSourceSearch"

// Provider timeouts were 12s / 10s — bumped them down to 6s / 5s. Real
// trackers respond in well under a second when they're up; anything
// taking longer than 5–6s is almost certainly dead from our perspective
// and we'd rather show partial results than wait. Cuts worst-case search
// time roughly in half.
private const val SEARCH_TIMEOUT_MS = 6_000L
private const val DETAIL_TIMEOUT_MS = 5_000L
private const val DEFAULT_LIMIT = 12
private const val DETAIL_FETCH_LIMIT = 8
private const val NYAA_FETCH_LIMIT = 28
// Nyaa fan-out cap. These queries all hit nyaa.si, now gated by a
// 3-permit semaphore — so the count is "how deep we probe", not "how
// many run at once". 6 covers romaji + English across the main S/E
// format variants (the kanji title rarely matches Nyaa's romaji index)
// without dragging the search into rate-limit territory.
private const val MAX_NYAA_QUERY_COUNT = 6
private const val MAX_NYAA_GROUP_QUERY_COUNT = 4
private const val TOKYOTOSHO_FETCH_LIMIT = 28
private const val BANGUMI_FETCH_LIMIT = 28
private const val FETCH_TEXT_CACHE_TTL_MS = 30_000L

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36 CyferStreaming/0.1"

internal val PUBLIC_TORRENT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
)

private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// ═══════════════════════════════════════════════════════════════
//  HTTP fetch + per-host rate limiting + short-TTL cache
// ═══════════════════════════════════════════════════════════════

private data class CachedText(val expiresAt: Long, val text: String)

private val fetchTextCache = ConcurrentHashMap<String, CachedText>()
private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()

private fun hostForUrl(url: String): String =
    runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

/**
 * Concurrent requests permitted per host. The previous model used a
 * per-host Mutex *plus* an 850 ms cooldown, fully serialising every
 * request to a host — catastrophic for anime, where nyaa, Erai-raws and
 * ToonsHub all hit nyaa.si and the search fans out ~10 queries: that's
 * ~15 s of sequential round-trips before a single result appears.
 *
 * A small semaphore keeps us polite (nyaa 429s if hammered) while
 * letting the fan-out actually run in parallel. nyaa gets a tighter cap
 * than general trackers because it rate-limits hardest.
 */
private fun maxConcurrentForHost(host: String): Int =
    if (Regex("(^|\\.)nyaa\\.", RegexOption.IGNORE_CASE).containsMatchIn(host)) 3 else 5

private suspend fun <T> withHostFetchSlot(url: String, task: suspend () -> T): T {
    val host = hostForUrl(url)
    if (host.isEmpty()) return task()
    val sem = hostSemaphores.getOrPut(host) { Semaphore(maxConcurrentForHost(host)) }
    return sem.withPermit { task() }
}

private suspend fun fetchText(url: String, timeoutMs: Long): String {
    fetchTextCache[url]?.let { cached ->
        if (cached.expiresAt > System.currentTimeMillis()) return cached.text
    }
    val text = withHostFetchSlot(url) {
        withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
                httpClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}")
                    res.body?.string() ?: ""
                }
            }
        }
    }
    fetchTextCache[url] = CachedText(System.currentTimeMillis() + FETCH_TEXT_CACHE_TTL_MS, text)
    return text
}

// ═══════════════════════════════════════════════════════════════
//  HTML / RSS helpers
// ═══════════════════════════════════════════════════════════════

private fun cleanBaseUrl(value: String): String = value.trim().trimEnd('/')

private fun absoluteUrl(baseUrl: String, href: String): String =
    runCatching { URI(baseUrl).resolve(href).toString() }.getOrElse { href }

private fun decodeCodePoint(value: String, radix: Int): String {
    val code = value.toIntOrNull(radix) ?: return " "
    if (code < 0 || code > 0x10FFFF) return " "
    return runCatching { String(Character.toChars(code)) }.getOrDefault(" ")
}

private fun htmlDecode(value: String): String = value
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace(Regex("&#0*39;|&#x0*27;|&apos;", RegexOption.IGNORE_CASE), "'")
    .replace("&lt;", "<")
    .replace("&gt;", " ")
    .replace("&nbsp;", " ")
    .replace(Regex("&#(\\d+);")) { decodeCodePoint(it.groupValues[1], 10) }
    .replace(Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)) { decodeCodePoint(it.groupValues[1], 16) }

private fun stripHtml(value: String): String =
    htmlDecode(value.replace(Regex("<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ")
        .trim()

private fun rssTag(block: String, tag: String): String? {
    val r = Regex("<${Regex.escape(tag)}(?:\\s[^>]*)?>([\\s\\S]*?)</${Regex.escape(tag)}>", RegexOption.IGNORE_CASE)
    val m = r.find(block) ?: return null
    return stripHtml(m.groupValues[1].replace(Regex("^<!\\[CDATA\\[([\\s\\S]*)\\]\\]>$", RegexOption.IGNORE_CASE), "$1"))
}

private fun parseInteger(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val digits = value.replace(Regex("[^\\d]"), "")
    return digits.toIntOrNull()
}

private fun jsonString(value: JsonElement?): String =
    (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun jsonNumber(value: JsonElement?): Long? {
    val prim = value as? JsonPrimitive ?: return null
    return prim.longOrNull ?: prim.contentOrNull?.replace(",", "")?.toLongOrNull()
}

private fun containsChallenge(html: String): Boolean =
    Regex("Just a moment|cf_chl|challenge-platform|Enable JavaScript and cookies", RegexOption.IGNORE_CASE)
        .containsMatchIn(html)

private fun containsUsableTorrentContent(html: String): Boolean =
    Regex("magnet:\\?xt=urn:btih:|href=[\"']/torrent/|href=[\"']/post-detail/", RegexOption.IGNORE_CASE)
        .containsMatchIn(html)

private fun isBlockingChallenge(html: String): Boolean =
    containsChallenge(html) && !containsUsableTorrentContent(html)

// ═══════════════════════════════════════════════════════════════
//  Magnet + size helpers
// ═══════════════════════════════════════════════════════════════

internal fun extractMagnet(html: String): String? {
    val m = Regex("magnet:\\?xt=urn:btih:[^\"' <>\r\n]+", RegexOption.IGNORE_CASE).find(html) ?: return null
    return htmlDecode(m.value)
}

internal fun extractInfoHash(magnet: String): String? =
    Regex("btih:([a-z0-9]{32,40})", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.getOrNull(1)?.lowercase()

internal fun buildMagnet(infoHash: String, title: String): String {
    val name = URLEncoder.encode(title.ifBlank { infoHash }, "UTF-8")
    val trackers = PUBLIC_TORRENT_TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
    return "magnet:?xt=urn:btih:$infoHash&dn=$name$trackers"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

// ═══════════════════════════════════════════════════════════════
//  Query building + title scoring
// ═══════════════════════════════════════════════════════════════

private val DIACRITICS_RE = Regex("[\\u0300-\\u036f]")

private fun foldSearchText(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).replace(DIACRITICS_RE, "")

private fun hasLatinSearchSignal(value: String): Boolean =
    Regex("[a-z]", RegexOption.IGNORE_CASE).containsMatchIn(foldSearchText(value))

private fun normaliseTitlePhrase(value: String): String =
    foldSearchText(value).lowercase().replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()

private fun derivedSearchTitles(title: String, request: TorrentSourceSearchRequest): List<String> {
    if (request.mediaType != TorrentMediaType.anime) return emptyList()
    val out = mutableListOf<String>()
    val colonBase = title.split(Regex("\\s*[:：]\\s*"))[0].trim()
    if (colonBase.length in 3 until title.trim().length) out += colonBase
    val seasonBase = title.replace(Regex("\\s+(?:season|series|part)\\s+\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
    if (seasonBase.length in 3 until title.trim().length) out += seasonBase
    return out
}

private fun searchTitlesFor(request: TorrentSourceSearchRequest): List<String> {
    val seen = mutableSetOf<String>()
    val primary = listOf(request.title) + request.alternateTitles
    val all = primary + primary.flatMap { derivedSearchTitles(it, request) }
    return all
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { title ->
            val phrase = normaliseTitlePhrase(title)
            val key = if (hasLatinSearchSignal(title) && phrase.isNotEmpty()) phrase
            else foldSearchText(title).lowercase()
            if (key in seen) false else { seen += key; true }
        }
}

private fun titleTokensFor(request: TorrentSourceSearchRequest): List<String> {
    val seen = mutableSetOf<String>()
    return searchTitlesFor(request)
        .flatMap { normaliseTitlePhrase(it).split(Regex("\\s+")) }
        .filter { it.length > 2 }
        .filter { if (it in seen) false else { seen += it; true } }
}

private fun animeQueryTitlesFor(titles: List<String>): List<String> = titles.sortedWith(
    Comparator { a, b ->
        val aLatin = hasLatinSearchSignal(a)
        val bLatin = hasLatinSearchSignal(b)
        if (aLatin != bLatin) return@Comparator if (aLatin) -1 else 1
        if (aLatin && bLatin) {
            val aw = normaliseTitlePhrase(a).split(Regex("\\s+")).count { it.isNotBlank() }
            val bw = normaliseTitlePhrase(b).split(Regex("\\s+")).count { it.isNotBlank() }
            if (aw != bw) return@Comparator aw - bw
        }
        a.length - b.length
    },
)

private fun animeSeasonNumbersFor(request: TorrentSourceSearchRequest): List<Int> {
    val seasons = linkedSetOf<Int>()
    val titles = listOf(request.title) + request.alternateTitles
    val patterns = listOf(
        Regex("\\b(?:season|series)\\s*0*(\\d{1,2})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b0*(\\d{1,2})(?:st|nd|rd|th)\\s+(?:season|series)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bs\\s*0*(\\d{1,2})\\b", RegexOption.IGNORE_CASE),
        Regex("第\\s*0*(\\d{1,2})\\s*期", RegexOption.IGNORE_CASE),
    )
    for (title in titles) {
        val folded = foldSearchText(title)
        for (p in patterns) {
            for (m in p.findAll(folded)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                if (n in 1..30) seasons += n
            }
        }
    }
    if (seasons.isNotEmpty()) return seasons.toList()
    return request.season?.let { listOf(it) } ?: emptyList()
}

private fun isEpisodeSearch(request: TorrentSourceSearchRequest): Boolean =
    request.mediaType == TorrentMediaType.tv ||
        (request.mediaType == TorrentMediaType.anime && request.season != null && request.episode != null)

private fun isMovieSearch(request: TorrentSourceSearchRequest): Boolean =
    request.mediaType == TorrentMediaType.movie ||
        (request.mediaType == TorrentMediaType.anime && !isEpisodeSearch(request))

private fun encodeSearchPathSegment(value: String): String =
    URLEncoder.encode(value.trim(), "UTF-8").replace("%20", "+")

private fun buildQuery(request: TorrentSourceSearchRequest): String {
    val ep = request.episode
    val baseTitle = if (request.mediaType == TorrentMediaType.anime && ep != null) {
        animeQueryTitlesFor(searchTitlesFor(request)).firstOrNull()?.takeIf { it.isNotBlank() } ?: request.title.trim()
    } else request.title.trim()

    if (request.mediaType == TorrentMediaType.anime && ep != null) {
        val animeSeason = animeSeasonNumbersFor(request).firstOrNull()
        return if (animeSeason != null) {
            "$baseTitle S${"%02d".format(animeSeason)}E${"%02d".format(ep)}"
        } else {
            "$baseTitle ${"%02d".format(ep)}"
        }
    }
    if (request.mediaType == TorrentMediaType.tv && request.season != null && ep != null) {
        return "$baseTitle S${"%02d".format(request.season)}E${"%02d".format(ep)}"
    }
    return listOfNotNull(baseTitle.takeIf { it.isNotBlank() }, request.year).joinToString(" ")
}

private fun addQuery(out: MutableList<String>, seen: MutableSet<String>, value: String) {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return
    val key = normaliseTitlePhrase(trimmed).ifEmpty { foldSearchText(trimmed).lowercase() }
    if (key in seen) return
    seen += key
    out += trimmed
}

private fun buildNyaaQueries(request: TorrentSourceSearchRequest): List<String> {
    val titles = if (request.mediaType == TorrentMediaType.anime)
        animeQueryTitlesFor(searchTitlesFor(request))
    else
        searchTitlesFor(request)
    if (titles.isEmpty()) return emptyList()
    val queries = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    val ep = request.episode
    if (ep != null) {
        val episode = "%02d".format(ep)
        val raw = ep.toString()
        val season = "%02d".format(request.season ?: 1)
        if (request.mediaType == TorrentMediaType.anime) {
            for (animeSeason in animeSeasonNumbersFor(request)) {
                val animeSeasonText = "%02d".format(animeSeason)
                for (title in titles) addQuery(queries, seen, "$title S${animeSeasonText}E$episode")
            }
            for (title in titles) addQuery(queries, seen, "$title $episode")
            if (raw != episode) for (title in titles) addQuery(queries, seen, "$title $raw")
            for (title in titles) addQuery(queries, seen, "$title - $episode")
            return queries.take(MAX_NYAA_QUERY_COUNT)
        }
        for (title in titles) {
            addQuery(queries, seen, "$title $episode")
            addQuery(queries, seen, "$title - $episode")
            addQuery(queries, seen, "$title S${season}E$episode")
        }
        return queries.take(MAX_NYAA_QUERY_COUNT)
    }
    for (title in titles) {
        addQuery(queries, seen, title)
        request.year?.let { addQuery(queries, seen, "$title $it") }
    }
    return queries.take(MAX_NYAA_QUERY_COUNT)
}

private fun withReleaseGroupQueries(queries: List<String>, group: String): List<String> {
    val bracketed = "[$group]"
    val out = linkedSetOf<String>()
    for (q in queries) {
        val t = q.trim()
        if (t.isNotEmpty()) out += "$bracketed $t"
    }
    return out.take(MAX_NYAA_GROUP_QUERY_COUNT)
}

private fun isReleaseGroupResult(title: String, group: String): Boolean =
    normaliseTitlePhrase(group) in normaliseTitlePhrase(title)

private fun titleScore(name: String, request: TorrentSourceSearchRequest): Int {
    val haystack = name.lowercase()
    val normalised = normaliseTitlePhrase(name)
    var score = 0
    for (title in searchTitlesFor(request)) {
        val phrase = normaliseTitlePhrase(title)
        if (phrase.isNotEmpty() && phrase in normalised) score += 8
    }
    for (token in titleTokensFor(request)) {
        if (token in normalised) score += 3
    }
    request.year?.let { if (it in haystack) score += 5 }
    val ep = request.episode
    if ((request.mediaType == TorrentMediaType.tv && request.season != null && ep != null)
        || (request.mediaType == TorrentMediaType.anime && ep != null)
    ) {
        val episode = "%02d".format(ep)
        val seasons = if (request.mediaType == TorrentMediaType.anime)
            animeSeasonNumbersFor(request) else listOfNotNull(request.season)
        for (s in seasons) {
            val season = "%02d".format(s)
            if (Regex("s0*$s[^a-z0-9]?e0*$ep", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 10
            if (haystack.contains("$season$episode") || haystack.contains("${s}x$ep")) score += 4
        }
    }
    if (request.mediaType == TorrentMediaType.anime && ep != null) {
        val episode = "%02d".format(ep)
        if (Regex("(^|[^0-9])0*$ep([^0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 8
        if (Regex("[-._\\s]$episode[-._\\s\\])}]", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 5
        if (Regex("\\b(batch|complete|season\\s+\\d+|collection)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) score -= 3
    }
    if (Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 3
    if (Regex("\\b(1080p|bluray|web-dl|webrip)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 2
    if (Regex("\\b(cam|telesync|ts|hdcam)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) score -= 12
    if (Regex("\\b(xxx|porn|hentai)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) score -= 20
    return score
}

private fun <T> sortResults(results: List<T>, request: TorrentSourceSearchRequest, key: (T) -> Pair<String, Int?>): List<T> =
    results.sortedWith(Comparator { a, b ->
        val ka = key(a); val kb = key(b)
        val scoreDelta = titleScore(kb.first, request) - titleScore(ka.first, request)
        if (scoreDelta != 0) scoreDelta else (kb.second ?: 0) - (ka.second ?: 0)
    })

private fun sortTorrentResults(list: List<TorrentSourceResult>, request: TorrentSourceSearchRequest) =
    sortResults(list, request) { it.title to it.seeders }

// ═══════════════════════════════════════════════════════════════
//  Pre-magnet row type for HTML providers that need detail fetch
// ═══════════════════════════════════════════════════════════════

private data class PreResult(
    val providerId: TorrentSourceProviderId,
    val providerName: String,
    val title: String,
    val detailUrl: String,
    val size: String?,
    val seeders: Int?,
    val leechers: Int?,
    val publishedAt: String?,
    val infoHash: String? = null,
    /** Some providers (MagnetDL) need to follow a secondary URL for the magnet. */
    val magnetSourceUrl: String? = null,
)

private fun PreResult.withMagnet(magnet: String): TorrentSourceResult = TorrentSourceResult(
    providerId = providerId,
    providerName = providerName,
    title = title,
    magnet = magnet,
    infoHash = infoHash ?: extractInfoHash(magnet),
    detailUrl = detailUrl,
    size = size,
    seeders = seeders,
    leechers = leechers,
    publishedAt = publishedAt,
)

private suspend fun hydrateMagnets(rows: List<PreResult>, limit: Int): List<TorrentSourceResult> {
    val candidates = rows.take(limit.coerceAtLeast(1))
    return supervisorScope {
        candidates.map { row ->
            async {
                runCatching {
                    val url = row.magnetSourceUrl ?: row.detailUrl
                    val html = fetchText(url, DETAIL_TIMEOUT_MS)
                    val magnet = extractMagnet(html)
                    if (magnet == null && isBlockingChallenge(html)) throw RuntimeException("Provider challenge page")
                    if (magnet == null) throw RuntimeException("No magnet found")
                    row.withMagnet(magnet)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }
}

// ═══════════════════════════════════════════════════════════════
//  Provider implementations
// ═══════════════════════════════════════════════════════════════

private fun parseRargbRows(html: String, baseUrl: String): List<PreResult> {
    val rows = html.split(Regex("<tr\\s+class=[\"']lista2[\"'][^>]*>", RegexOption.IGNORE_CASE)).drop(1)
    val seen = mutableSetOf<String>()
    val results = mutableListOf<PreResult>()
    for (row in rows) {
        val link = Regex("href=[\"'](/torrent/[^\"']+)[\"'][^>]*title=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(row) ?: continue
        val href = link.groupValues[1]
        if (href in seen) continue
        seen += href
        val cells = Regex("<td\\b[^>]*>[\\s\\S]*?</td>", RegexOption.IGNORE_CASE).findAll(row).toList()
        val date = cells.getOrNull(3)?.value?.let { stripHtml(it) }
        val size = cells.getOrNull(4)?.value?.let { stripHtml(it) }
        val seeders = cells.getOrNull(5)?.value?.let { parseInteger(stripHtml(it)) }
        val leechers = cells.getOrNull(6)?.value?.let { parseInteger(stripHtml(it)) }
        results += PreResult(
            providerId = TorrentSourceProviderId.rargb,
            providerName = "RARBG-compatible",
            title = stripHtml(link.groupValues[2]),
            detailUrl = absoluteUrl(baseUrl, href),
            size = size,
            seeders = seeders,
            leechers = leechers,
            publishedAt = date,
        )
    }
    return results
}

private suspend fun searchRargb(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val query = buildQuery(request)
    val params = StringBuilder("?search=${URLEncoder.encode(query, "UTF-8")}")
    if (isMovieSearch(request)) params.append("&category%5B%5D=movies")
    if (isEpisodeSearch(request)) params.append("&category%5B%5D=tv")
    if (request.mediaType == TorrentMediaType.anime) params.append("&category%5B%5D=anime")
    val html = fetchText("$baseUrl/search/$params", SEARCH_TIMEOUT_MS)
    val sorted = sortResults(parseRargbRows(html, baseUrl), request) { it.title to it.seeders }
    if (sorted.isEmpty() && isBlockingChallenge(html)) throw RuntimeException("Provider returned a browser challenge")
    return hydrateMagnets(sorted, (request.limit).coerceAtMost(DETAIL_FETCH_LIMIT))
}

private fun parse1337xRows(html: String, baseUrl: String): List<PreResult> {
    val rows = Regex("<tr\\b[\\s\\S]*?</tr>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
    val seen = mutableSetOf<String>()
    val results = mutableListOf<PreResult>()
    for (row in rows) {
        val link = Regex("href=[\"'](/torrent/[^\"']+)[\"'][^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE).find(row) ?: continue
        val href = link.groupValues[1]
        if (href in seen) continue
        seen += href
        val seeders = parseInteger(Regex("class=[\"'][^\"']*seeds[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).find(row)?.groupValues?.getOrNull(1))
        val leechers = parseInteger(Regex("class=[\"'][^\"']*leeches[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).find(row)?.groupValues?.getOrNull(1))
        val size = Regex("class=[\"'][^\"']*size[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).find(row)?.groupValues?.getOrNull(1)?.let { stripHtml(it) }?.takeIf { it.isNotEmpty() }
        results += PreResult(
            providerId = TorrentSourceProviderId.`1337x`,
            providerName = "1337x",
            title = stripHtml(link.groupValues[2]),
            detailUrl = absoluteUrl(baseUrl, href),
            size = size,
            seeders = seeders,
            leechers = leechers,
            publishedAt = null,
        )
    }
    return results
}

private val STOP_WORDS = setOf("the", "and", "for", "but", "not", "with", "from", "this", "that", "are", "was", "were", "has", "have", "had", "its", "you", "all")

private suspend fun search1337x(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val category = if (isMovieSearch(request)) "Movies" else "TV"
    val fullQuery = encodeSearchPathSegment(buildQuery(request))
    val titleWords = request.title.split(Regex("[^a-z0-9]+", RegexOption.IGNORE_CASE))
        .filter { it.length > 2 && it.lowercase() !in STOP_WORDS }
        .sortedByDescending { it.length }
    val queries = mutableListOf(fullQuery)
    for (word in titleWords.take(2)) {
        val q = encodeSearchPathSegment(word)
        if (queries.none { it.equals(q, ignoreCase = true) }) queries += q
    }

    val seen = mutableSetOf<String>()
    val allRows = mutableListOf<PreResult>()
    var challengeDetected = false

    coroutineScope {
        queries.map { q ->
            async {
                runCatching {
                    val html = fetchText("$baseUrl/category-search/$q/$category/1/", SEARCH_TIMEOUT_MS)
                    val rows = parse1337xRows(html, baseUrl)
                    if (rows.isEmpty() && isBlockingChallenge(html)) challengeDetected = true
                    rows
                }.getOrElse { emptyList() }
            }
        }.awaitAll().forEach { rows ->
            for (r in rows) {
                if (r.detailUrl in seen) continue
                seen += r.detailUrl
                allRows += r
            }
        }
    }

    if (allRows.isEmpty() && challengeDetected) throw RuntimeException("Provider returned a browser challenge")
    val sorted = sortResults(allRows, request) { it.title to it.seeders }
    return hydrateMagnets(sorted, request.limit.coerceAtMost(DETAIL_FETCH_LIMIT))
}

private fun parseNyaaRss(xml: String, baseUrl: String): List<TorrentSourceResult> {
    val items = Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }.toList()
    val seen = mutableSetOf<String>()
    val results = mutableListOf<TorrentSourceResult>()
    for (item in items) {
        val title = rssTag(item, "title")
        val infoHash = rssTag(item, "nyaa:infoHash")?.lowercase()
        val torrentUrl = rssTag(item, "link")
        val detailUrl = rssTag(item, "guid") ?: torrentUrl ?: baseUrl
        val source = if (infoHash != null) buildMagnet(infoHash, title ?: infoHash) else torrentUrl
        if (title.isNullOrBlank() || source.isNullOrBlank()) continue
        val key = infoHash ?: source
        if (key in seen) continue
        seen += key
        results += TorrentSourceResult(
            providerId = TorrentSourceProviderId.nyaa,
            providerName = "Nyaa",
            title = title,
            magnet = source,
            infoHash = infoHash,
            detailUrl = absoluteUrl(baseUrl, detailUrl),
            size = rssTag(item, "nyaa:size"),
            seeders = parseInteger(rssTag(item, "nyaa:seeders")),
            leechers = parseInteger(rssTag(item, "nyaa:leechers")),
            publishedAt = rssTag(item, "pubDate"),
        )
    }
    return results
}

private suspend fun searchNyaa(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> = coroutineScope {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val queries = buildNyaaQueries(request)
    Log.i(TAG, "searchNyaa: ${queries.size} queries: $queries")
    val byKey = LinkedHashMap<String, TorrentSourceResult>()
    val errors = mutableListOf<String>()
    // Was a sequential for-loop. Fanning out concurrently cuts Nyaa from
    // ~queries.size × 1s → ~1s total. Even if half the queries time out
    // the slowest one still bounds the wall-clock.
    val perQuery = queries.map { query ->
        async {
            try {
                val url = "$baseUrl/?page=rss&q=${URLEncoder.encode(query, "UTF-8")}&c=1_0&f=0"
                val xml = fetchText(url, SEARCH_TIMEOUT_MS)
                Log.i(TAG, "  ← $query: ${xml.length} bytes")
                if (!Regex("<rss\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml) && isBlockingChallenge(xml))
                    throw RuntimeException("Provider returned a browser challenge")
                parseNyaaRss(xml, baseUrl)
            } catch (err: Throwable) {
                synchronized(errors) { errors += err.message ?: err.toString() }
                emptyList()
            }
        }
    }.awaitAll()
    for (parsed in perQuery) {
        for (item in parsed) {
            byKey.putIfAbsent(item.infoHash ?: item.magnet, item)
            if (byKey.size >= NYAA_FETCH_LIMIT) break
        }
        if (byKey.size >= NYAA_FETCH_LIMIT) break
    }
    if (byKey.isEmpty() && errors.isNotEmpty()) throw RuntimeException(errors.first())
    sortTorrentResults(byKey.values.toList(), request).take(request.limit.coerceAtMost(NYAA_FETCH_LIMIT))
}

private fun remapProviderResults(provider: TorrentSourceProviderSettings, results: List<TorrentSourceResult>): List<TorrentSourceResult> =
    results.map { it.copy(providerId = provider.id, providerName = provider.name) }

private suspend fun searchNyaaReleaseGroup(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest, group: String): List<TorrentSourceResult> = coroutineScope {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val queries = withReleaseGroupQueries(buildNyaaQueries(request), group)
    val byKey = LinkedHashMap<String, TorrentSourceResult>()
    val errors = mutableListOf<String>()
    val perQuery = queries.map { query ->
        async {
            try {
                val url = "$baseUrl/?page=rss&q=${URLEncoder.encode(query, "UTF-8")}&c=1_0&f=0"
                val xml = fetchText(url, SEARCH_TIMEOUT_MS)
                if (!Regex("<rss\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml) && isBlockingChallenge(xml))
                    throw RuntimeException("Provider returned a browser challenge")
                parseNyaaRss(xml, baseUrl).filter { isReleaseGroupResult(it.title, group) }
            } catch (err: Throwable) {
                synchronized(errors) { errors += err.message ?: err.toString() }
                emptyList()
            }
        }
    }.awaitAll()
    for (parsed in perQuery) {
        for (item in parsed) {
            val remapped = item.copy(providerId = provider.id, providerName = provider.name)
            byKey.putIfAbsent(remapped.infoHash ?: remapped.magnet, remapped)
        }
    }
    if (byKey.isEmpty() && errors.isNotEmpty()) throw RuntimeException(errors.first())
    sortTorrentResults(byKey.values.toList(), request).take(request.limit.coerceAtMost(NYAA_FETCH_LIMIT))
}

private fun parseKickassRows(html: String, baseUrl: String): List<PreResult> {
    val rows = Regex("<tr\\b[\\s\\S]*?</tr>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
    val seen = mutableSetOf<String>()
    val results = mutableListOf<PreResult>()
    for (row in rows) {
        val link = Regex("href=[\"'](/[^\"']*-t\\d+\\.html)[\"'][^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE).find(row)
            ?: Regex("href=[\"'](/torrent/[^\"']+)[\"'][^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE).find(row)
            ?: continue
        val href = link.groupValues[1]
        if (href in seen) continue
        seen += href
        val cells = Regex("<td\\b[^>]*>[\\s\\S]*?</td>", RegexOption.IGNORE_CASE).findAll(row).map { it.value }.toList()
        val seeders = parseInteger(Regex("class=[\"'][^\"']*green[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).find(row)?.groupValues?.getOrNull(1))
        val leechers = parseInteger(Regex("class=[\"'][^\"']*red[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE).find(row)?.groupValues?.getOrNull(1))
        var size: String? = null
        for (cell in cells) {
            val text = stripHtml(cell)
            val sm = Regex("[\\d.]+\\s*(?:GB|MB|KB|TB)\\b", RegexOption.IGNORE_CASE).find(text)
            if (sm != null) { size = sm.value; break }
        }
        results += PreResult(
            providerId = TorrentSourceProviderId.kickass,
            providerName = "KickassTorrents",
            title = stripHtml(link.groupValues[2]),
            detailUrl = absoluteUrl(baseUrl, href),
            size = size, seeders = seeders, leechers = leechers, publishedAt = null,
        )
    }
    return results
}

private suspend fun searchKickass(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val query = URLEncoder.encode(buildQuery(request), "UTF-8")
    val html = fetchText("$baseUrl/usearch/$query/", SEARCH_TIMEOUT_MS)
    val sorted = sortResults(parseKickassRows(html, baseUrl), request) { it.title to it.seeders }
    if (sorted.isEmpty() && isBlockingChallenge(html)) throw RuntimeException("Provider returned a browser challenge")
    return hydrateMagnets(sorted, request.limit.coerceAtMost(DETAIL_FETCH_LIMIT))
}

private fun parseTpbResults(arr: JsonArray, baseUrl: String): List<TorrentSourceResult> {
    val out = mutableListOf<TorrentSourceResult>()
    for (item in arr) {
        val obj = item as? JsonObject ?: continue
        val name = jsonString(obj["name"])
        if (name.isEmpty() || name == "No results returned") continue
        val infoHash = jsonString(obj["info_hash"]).lowercase()
        if (infoHash.isEmpty() || infoHash.matches(Regex("^0+$"))) continue
        val sizeBytes = jsonNumber(obj["size"]) ?: 0L
        val size = if (sizeBytes > 0) formatBytes(sizeBytes) else null
        val added = jsonNumber(obj["added"]) ?: 0L
        val published = if (added > 0) java.time.Instant.ofEpochSecond(added).toString() else null
        out += TorrentSourceResult(
            providerId = TorrentSourceProviderId.tpb,
            providerName = "The Pirate Bay",
            title = name,
            magnet = buildMagnet(infoHash, name),
            infoHash = infoHash,
            detailUrl = "$baseUrl/description.php?id=${jsonString(obj["id"])}",
            size = size,
            seeders = parseInteger(jsonString(obj["seeders"])),
            leechers = parseInteger(jsonString(obj["leechers"])),
            publishedAt = published,
        )
    }
    return out
}

private suspend fun searchTpb(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val query = buildQuery(request)
    val cat = if (isMovieSearch(request)) "201,207" else "205,208,209"
    val url = "$baseUrl/q.php?q=${URLEncoder.encode(query, "UTF-8")}&cat=$cat"
    val text = fetchText(url, SEARCH_TIMEOUT_MS)
    val parsed = runCatching { json.parseToJsonElement(text) }.getOrElse {
        if (isBlockingChallenge(text)) throw RuntimeException("Provider returned a browser challenge")
        throw RuntimeException("Invalid response from provider")
    }
    val arr = (parsed as? JsonArray) ?: return emptyList()
    return sortTorrentResults(parseTpbResults(arr, baseUrl), request).take(request.limit)
}

private fun parseTorrentGalaxyRows(html: String, baseUrl: String): List<PreResult> {
    val rows = html.split(Regex("class=[\"'][^\"']*tgxtablerow[^\"']*[\"']", RegexOption.IGNORE_CASE)).drop(1)
    val seen = mutableSetOf<String>()
    val results = mutableListOf<PreResult>()
    for (raw in rows) {
        val rowHtml = raw.split(Regex("class=[\"'][^\"']*tgxtablerow", RegexOption.IGNORE_CASE)).firstOrNull() ?: raw
        val link = Regex("href=[\"'](/post-detail/[^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).find(rowHtml) ?: continue
        val href = link.groupValues[1]
        if (href in seen) continue
        seen += href
        val seeders = parseInteger(Regex("<font[^>]*color=[\"']green[\"'][^>]*>[\\s\\S]*?<b>(\\d+)</b>", RegexOption.IGNORE_CASE).find(rowHtml)?.groupValues?.getOrNull(1))
        val leechers = parseInteger(Regex("<font[^>]*color=[\"']#ff0000[\"'][^>]*>[\\s\\S]*?<b>(\\d+)</b>", RegexOption.IGNORE_CASE).find(rowHtml)?.groupValues?.getOrNull(1))
        val size = Regex("([\\d.]+\\s*(?:GB|MB|KB|TB))</span>", RegexOption.IGNORE_CASE).find(rowHtml)?.groupValues?.getOrNull(1)?.trim()
        val date = Regex("data-timestamp=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(rowHtml)?.groupValues?.getOrNull(1)
        results += PreResult(
            providerId = TorrentSourceProviderId.torrentgalaxy,
            providerName = "TorrentGalaxy",
            title = stripHtml(link.groupValues[2]),
            detailUrl = absoluteUrl(baseUrl, href),
            size = size, seeders = seeders, leechers = leechers, publishedAt = date,
        )
    }
    return results
}

private suspend fun searchTorrentGalaxy(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val query = URLEncoder.encode(buildQuery(request), "UTF-8")
    val html = fetchText("$baseUrl/get-posts/keywords:$query", SEARCH_TIMEOUT_MS)
    val sorted = sortResults(parseTorrentGalaxyRows(html, baseUrl), request) { it.title to it.seeders }
    if (sorted.isEmpty() && isBlockingChallenge(html)) throw RuntimeException("Provider returned a browser challenge")
    return hydrateMagnets(sorted, request.limit.coerceAtMost(DETAIL_FETCH_LIMIT))
}

private fun parseMagnetDlRows(html: String): List<PreResult> {
    val rows = Regex("<tr\\b[\\s\\S]*?</tr>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
    val results = mutableListOf<PreResult>()
    val seen = mutableSetOf<String>()
    for (row in rows) {
        if (row.contains("class=\"header\"")) continue
        val magnetApp = Regex("href=\"(https://magnetdl\\.app/single/\\d+)\"", RegexOption.IGNORE_CASE).find(row) ?: continue
        val magnetAppUrl = magnetApp.groupValues[1]
        val titleMatch = Regex(
            "(?:class=\"csprite[^\"]*\"></a>)?<a\\s[^>]*href=\"https?://magnetdl\\.co/single/\\d+\"[^>]*>([^<]+)</a>",
            RegexOption.IGNORE_CASE,
        ).find(row) ?: continue
        val title = stripHtml(titleMatch.groupValues[1])
        if (magnetAppUrl in seen) continue
        seen += magnetAppUrl
        val cells = Regex("<td\\b[^>]*>[\\s\\S]*?</td>", RegexOption.IGNORE_CASE).findAll(row).map { it.value }.toList()
        val size = cells.getOrNull(4)?.let { stripHtml(it) }?.takeIf { it.isNotEmpty() }
        val seeders = cells.getOrNull(5)?.let { parseInteger(stripHtml(it)) }
        val leechers = cells.getOrNull(6)?.let { parseInteger(stripHtml(it)) }
        val age = cells.getOrNull(2)?.let { stripHtml(it) }
        results += PreResult(
            providerId = TorrentSourceProviderId.magnetdl,
            providerName = "MagnetDL",
            title = title,
            detailUrl = magnetAppUrl,
            size = size, seeders = seeders, leechers = leechers, publishedAt = age,
            magnetSourceUrl = magnetAppUrl,
        )
    }
    return results
}

private suspend fun searchMagnetDl(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val url = "$baseUrl/search/?q=${URLEncoder.encode(buildQuery(request), "UTF-8")}&m=1"
    val html = fetchText(url, SEARCH_TIMEOUT_MS)
    val sorted = sortResults(parseMagnetDlRows(html), request) { it.title to it.seeders }
    if (sorted.isEmpty() && isBlockingChallenge(html)) throw RuntimeException("Provider returned a browser challenge")
    return hydrateMagnets(sorted, request.limit.coerceAtMost(DETAIL_FETCH_LIMIT))
}

private fun parseTokyoToshoRss(xml: String, baseUrl: String): List<TorrentSourceResult> {
    val items = Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }.toList()
    val seen = mutableSetOf<String>()
    val results = mutableListOf<TorrentSourceResult>()
    for (item in items) {
        val title = rssTag(item, "title") ?: continue
        val detailUrl = rssTag(item, "link") ?: rssTag(item, "guid") ?: baseUrl
        val pub = rssTag(item, "pubDate")
        val descBlock = Regex("<description(?:\\s[^>]*)?>[\\s\\S]*?</description>", RegexOption.IGNORE_CASE).find(item)?.value.orEmpty()
        val rawDesc = descBlock
            .replace(Regex("</?description[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^<!\\[CDATA\\[([\\s\\S]*)\\]\\]>$", RegexOption.IGNORE_CASE), "$1")
        val magnetMatch = Regex("magnet:\\?xt=urn:btih:[^\"' <>\r\n]+", RegexOption.IGNORE_CASE).find(rawDesc) ?: continue
        val magnet = htmlDecode(magnetMatch.value)
        val infoHash = Regex("btih:([a-f0-9]{40})", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.getOrNull(1)?.lowercase()
        val key = infoHash ?: magnet
        if (key in seen) continue
        seen += key
        val sizeMatch =
            Regex("Size:\\s*([\\d.]+\\s*(?:GB|MB|KB|TB))", RegexOption.IGNORE_CASE).find(rawDesc)
                ?: Regex("([\\d.]+\\s*(?:GB|MB|KB|TB))", RegexOption.IGNORE_CASE).find(rawDesc)
        results += TorrentSourceResult(
            providerId = TorrentSourceProviderId.tokyotosho,
            providerName = "TokyoTosho",
            title = title,
            magnet = magnet,
            infoHash = infoHash,
            detailUrl = absoluteUrl(baseUrl, detailUrl),
            size = sizeMatch?.groupValues?.getOrNull(1)?.trim(),
            seeders = null,
            leechers = null,
            publishedAt = pub,
        )
    }
    return results
}

private suspend fun searchTokyoTosho(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val queries = buildNyaaQueries(request)
    val byKey = LinkedHashMap<String, TorrentSourceResult>()
    val errors = mutableListOf<String>()
    coroutineScope {
        queries.map { q ->
            async {
                runCatching {
                    val url = "$baseUrl/rss.php?terms=${URLEncoder.encode(q, "UTF-8")}&type=1"
                    val xml = fetchText(url, SEARCH_TIMEOUT_MS)
                    if (!Regex("<rss\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml) && isBlockingChallenge(xml))
                        throw RuntimeException("Provider returned a browser challenge")
                    parseTokyoToshoRss(xml, baseUrl)
                }
            }
        }.awaitAll().forEach { res ->
            res.onSuccess { items ->
                for (item in items) byKey.putIfAbsent(item.infoHash ?: item.magnet, item)
            }
            res.onFailure { errors += it.message ?: it.toString() }
        }
    }
    if (byKey.isEmpty() && errors.isNotEmpty()) throw RuntimeException(errors.first())
    return sortTorrentResults(byKey.values.toList(), request).take(request.limit.coerceAtMost(TOKYOTOSHO_FETCH_LIMIT))
}

private fun isBangumiAnimeCategory(item: JsonObject): Boolean {
    val cat = item["category_tag"] as? JsonObject ?: return true
    val searchable = buildString {
        append(jsonString(cat["name"])); append(' ')
        (cat["synonyms"] as? JsonArray)?.forEach { append(jsonString(it)).append(' ') }
        (cat["locale"] as? JsonObject)?.values?.forEach { append(jsonString(it)).append(' ') }
    }.lowercase()
    if (searchable.isBlank()) return true
    return Regex("\\b(animation|anime)\\b|動畫|动画|アニメ", RegexOption.IGNORE_CASE).containsMatchIn(searchable)
}

private fun parseBangumiMoeResults(root: JsonElement, baseUrl: String): List<TorrentSourceResult> {
    val torrents: List<JsonElement> = when (root) {
        is JsonObject -> (root["torrents"] as? JsonArray)?.toList() ?: emptyList()
        is JsonArray -> root.toList()
        else -> emptyList()
    }
    val seen = mutableSetOf<String>()
    val results = mutableListOf<TorrentSourceResult>()
    for (raw in torrents) {
        val obj = raw as? JsonObject ?: continue
        if (!isBangumiAnimeCategory(obj)) continue
        val title = jsonString(obj["title"])
        val id = jsonString(obj["_id"])
        val rawMagnet = jsonString(obj["magnet"])
        val infoHash = (jsonString(obj["infoHash"]).ifEmpty { extractInfoHash(rawMagnet).orEmpty() }).lowercase().ifEmpty { null }
        val magnet = rawMagnet.ifEmpty { infoHash?.let { buildMagnet(it, title.ifEmpty { it }) }.orEmpty() }
        if (title.isEmpty() || magnet.isEmpty()) continue
        val key = infoHash ?: magnet
        if (key in seen) continue
        seen += key
        val sizeBytes = jsonNumber(obj["size"])
        val sizeStr = (obj["size"] as? JsonPrimitive)?.contentOrNull?.takeIf { (obj["size"] as? JsonPrimitive)?.intOrNull == null }?.trim()
        val size = when {
            !sizeStr.isNullOrEmpty() -> sizeStr
            sizeBytes != null && sizeBytes > 0 -> formatBytes(sizeBytes)
            else -> null
        }
        results += TorrentSourceResult(
            providerId = TorrentSourceProviderId.bangumi,
            providerName = "Bangumi.moe",
            title = title,
            magnet = magnet,
            infoHash = infoHash,
            detailUrl = if (id.isNotEmpty()) absoluteUrl(baseUrl, "/torrent/$id") else baseUrl,
            size = size,
            seeders = jsonNumber(obj["seeders"])?.toInt() ?: parseInteger(jsonString(obj["seeders"])),
            leechers = jsonNumber(obj["leechers"])?.toInt() ?: parseInteger(jsonString(obj["leechers"])),
            publishedAt = jsonString(obj["publish_time"]).ifEmpty { jsonString(obj["publishedAt"]) }.ifEmpty { null },
        )
    }
    return results
}

private suspend fun searchBangumiMoe(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val queries = buildNyaaQueries(request)
    val byKey = LinkedHashMap<String, TorrentSourceResult>()
    val errors = mutableListOf<String>()
    coroutineScope {
        queries.map { q ->
            async {
                runCatching {
                    val url = "$baseUrl/api/v2/torrent/search?query=${URLEncoder.encode(q, "UTF-8")}"
                    val text = fetchText(url, SEARCH_TIMEOUT_MS)
                    val parsed = runCatching { json.parseToJsonElement(text) }.getOrElse {
                        if (isBlockingChallenge(text)) throw RuntimeException("Provider returned a browser challenge")
                        throw RuntimeException("Invalid JSON response from provider")
                    }
                    parseBangumiMoeResults(parsed, baseUrl)
                }
            }
        }.awaitAll().forEach { res ->
            res.onSuccess { items -> for (item in items) byKey.putIfAbsent(item.infoHash ?: item.magnet, item) }
            res.onFailure { errors += it.message ?: it.toString() }
        }
    }
    if (byKey.isEmpty() && errors.isNotEmpty()) throw RuntimeException(errors.first())
    return sortTorrentResults(byKey.values.toList(), request).take(request.limit.coerceAtMost(BANGUMI_FETCH_LIMIT))
}

private fun parseAnidexRss(xml: String, baseUrl: String): List<PreResult> {
    val items = Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }.toList()
    val seen = mutableSetOf<String>()
    val results = mutableListOf<PreResult>()
    for (item in items) {
        val title = rssTag(item, "title") ?: continue
        val guid = rssTag(item, "guid").orEmpty()
        val link = rssTag(item, "link").orEmpty()
        val detailUrl = guid.ifEmpty { link }
        if (detailUrl.isEmpty()) continue
        if (detailUrl in seen) continue
        seen += detailUrl
        val pub = rssTag(item, "pubDate")
        val descBlock = Regex("<description(?:\\s[^>]*)?>[\\s\\S]*?</description>", RegexOption.IGNORE_CASE).find(item)?.value.orEmpty()
        val rawDesc = descBlock
            .replace(Regex("</?description[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^<!\\[CDATA\\[([\\s\\S]*)\\]\\]>$", RegexOption.IGNORE_CASE), "$1")
        val sizeMatch = Regex("([\\d.]+\\s*(?:GB|MB|KB|TB))", RegexOption.IGNORE_CASE).find(rawDesc)
        results += PreResult(
            providerId = TorrentSourceProviderId.anidex,
            providerName = "AniDex",
            title = title,
            detailUrl = absoluteUrl(baseUrl, detailUrl),
            size = sizeMatch?.groupValues?.getOrNull(1)?.trim(),
            seeders = null,
            leechers = null,
            publishedAt = pub,
        )
    }
    return results
}

private suspend fun searchAnidex(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val baseUrl = cleanBaseUrl(provider.baseUrl)
    val queries = buildNyaaQueries(request)
    val byUrl = LinkedHashMap<String, PreResult>()
    val errors = mutableListOf<String>()
    coroutineScope {
        queries.map { q ->
            async {
                runCatching {
                    val url = "$baseUrl/rss/?q=${URLEncoder.encode(q, "UTF-8")}&id=1"
                    val xml = fetchText(url, SEARCH_TIMEOUT_MS)
                    if (!Regex("<rss\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml) && isBlockingChallenge(xml))
                        throw RuntimeException("Provider returned a browser challenge")
                    parseAnidexRss(xml, baseUrl)
                }
            }
        }.awaitAll().forEach { res ->
            res.onSuccess { items -> for (item in items) byUrl.putIfAbsent(item.detailUrl, item) }
            res.onFailure { errors += it.message ?: it.toString() }
        }
    }
    if (byUrl.isEmpty() && errors.isNotEmpty()) throw RuntimeException(errors.first())
    val sorted = sortResults(byUrl.values.toList(), request) { it.title to it.seeders }
    return hydrateMagnets(sorted, request.limit.coerceAtMost(DETAIL_FETCH_LIMIT))
}

// ═══════════════════════════════════════════════════════════════
//  Provider dispatch + orchestrator
// ═══════════════════════════════════════════════════════════════

private suspend fun searchProvider(provider: TorrentSourceProviderSettings, request: TorrentSourceSearchRequest): List<TorrentSourceResult> {
    val isAnime = request.mediaType == TorrentMediaType.anime
    return when (provider.id) {
        TorrentSourceProviderId.nyaa -> if (isAnime) searchNyaa(provider, request) else emptyList()
        TorrentSourceProviderId.erairaws -> if (isAnime) searchNyaaReleaseGroup(provider, request, "Erai-raws") else emptyList()
        TorrentSourceProviderId.toonshub -> if (isAnime) searchNyaaReleaseGroup(provider, request, "ToonsHub") else emptyList()
        TorrentSourceProviderId.tokyotosho -> if (isAnime) searchTokyoTosho(provider, request) else emptyList()
        TorrentSourceProviderId.bangumi -> if (isAnime) searchBangumiMoe(provider, request) else emptyList()
        TorrentSourceProviderId.anidex -> if (isAnime) searchAnidex(provider, request) else emptyList()
        TorrentSourceProviderId.rargb -> searchRargb(provider, request)
        TorrentSourceProviderId.`1337x` -> search1337x(provider, request)
        TorrentSourceProviderId.kickass -> searchKickass(provider, request)
        TorrentSourceProviderId.tpb -> searchTpb(provider, request)
        TorrentSourceProviderId.torrentgalaxy -> searchTorrentGalaxy(provider, request)
        TorrentSourceProviderId.magnetdl -> searchMagnetDl(provider, request)
    }
}

private fun isNyaaBackedProvider(provider: TorrentSourceProviderSettings): Boolean =
    provider.id == TorrentSourceProviderId.nyaa
        || provider.id == TorrentSourceProviderId.erairaws
        || provider.id == TorrentSourceProviderId.toonshub

private fun resultToStream(result: TorrentSourceResult): ResolvedStream {
    val qualityText = listOfNotNull(result.title, result.size).joinToString(" ")
    val releaseGroup = detectAnimeReleaseGroup(result.title)
    return ResolvedStream(
        addonName = "Cyfer ${result.providerName}",
        addonId = "cyfer.torrent.${result.providerId.name}",
        playableUrl = result.magnet,
        quality = parseStreamQuality(qualityText),
        releaseGroup = releaseGroup,
        title = result.title,
        size = result.size,
        seeders = result.seeders,
        leechers = result.leechers,
        infoHash = result.infoHash,
        trackers = if (result.infoHash != null) PUBLIC_TORRENT_TRACKERS else emptyList(),
    )
}

/**
 * Main entry point — fans out to every enabled provider in parallel,
 * dedupes, reserves the top 2 per provider in phase 1, then fills the
 * remainder by global score in phase 2. Mirrors the desktop
 * `searchCyferTorrentSources`.
 */
suspend fun searchCyferTorrentSources(
    request: TorrentSourceSearchRequest,
): TorrentSearchOutcome = coroutineScope {
    val allEnabled = normaliseTorrentSourceProviders(request.providers ?: DEFAULT_TORRENT_SOURCE_PROVIDERS)
        .filter { it.enabled }
    val isAnime = request.mediaType == TorrentMediaType.anime
    Log.i(
        TAG,
        "searchCyferTorrentSources: '${request.title}' alts=${request.alternateTitles}" +
            " media=${request.mediaType} S${request.season ?: "-"}E${request.episode ?: "-"}" +
            " enabledProviders=${allEnabled.size}/${request.providers?.size ?: 0}",
    )
    // Provider scope rules:
    //   • Anime requests run *every* enabled provider — the anime
    //     trackers carry fansubs (HorribleSubs / Erai-raws / ToonsHub)
    //     and the general trackers carry cleartext BluRay rips that
    //     anime-only sites don't index. Restricting to anime-only
    //     misses big release groups, so we keep the union.
    //   • Non-anime (movie / TV) requests skip the anime trackers —
    //     Nyaa / Bangumi / AniDex don't carry Hollywood titles, so a
    //     round-trip to them is pure waste.
    val providers = if (isAnime) {
        allEnabled
    } else {
        allEnabled.filterNot { it.id in ANIME_ONLY_PROVIDERS }
    }
    val nyaaBacked = if (isAnime) providers.filter(::isNyaaBackedProvider) else emptyList()
    val others = if (nyaaBacked.isNotEmpty()) providers.filterNot(::isNyaaBackedProvider) else providers

    val errors = mutableListOf<TorrentSourceError>()
    val results = mutableListOf<TorrentSourceResult>()

    // Run the "others" fan-out concurrently with the Nyaa block instead
    // of after it. Previously: wall-clock = max(others) + nyaa (~20s).
    // Now:                    wall-clock = max(max(others), nyaa) (~10s).
    val othersJob = async {
        others.map { provider ->
            async {
                runCatching { searchProvider(provider, request) }
                    .onFailure { err ->
                        Log.w(TAG, "Provider ${provider.id} failed: ${err.message}")
                        synchronized(errors) {
                            errors += TorrentSourceError(provider.id, provider.name, err.message ?: err.toString())
                        }
                    }
                    .getOrDefault(emptyList())
                    .also { res -> synchronized(results) { results += res.map { it.copy(providerName = provider.name) } } }
            }
        }.awaitAll()
    }

    val nyaaJob = async {
        if (nyaaBacked.isEmpty()) return@async
        val primary = nyaaBacked.firstOrNull { it.id == TorrentSourceProviderId.nyaa } ?: nyaaBacked.first()
        val generic = runCatching {
            searchNyaa(primary, request.copy(limit = maxOf(request.limit, NYAA_FETCH_LIMIT)))
        }.onFailure { err ->
            synchronized(errors) {
                for (p in nyaaBacked) errors += TorrentSourceError(p.id, p.name, err.message ?: err.toString())
            }
        }.getOrDefault(emptyList())

        if (generic.isEmpty()) return@async

        // Fan out the release-group fallbacks concurrently too.
        val groupJobs = nyaaBacked.map { provider ->
            async {
                if (provider.id == TorrentSourceProviderId.nyaa) {
                    synchronized(results) { results += remapProviderResults(provider, generic) }
                    return@async
                }
                val group = when (provider.id) {
                    TorrentSourceProviderId.erairaws -> "Erai-raws"
                    TorrentSourceProviderId.toonshub -> "ToonsHub"
                    else -> null
                } ?: return@async
                val filtered = remapProviderResults(provider, generic.filter { isReleaseGroupResult(it.title, group) })
                if (filtered.isNotEmpty()) {
                    synchronized(results) { results += filtered }
                } else {
                    runCatching { searchNyaaReleaseGroup(provider, request, group) }
                        .onSuccess { synchronized(results) { results += it } }
                        .onFailure { synchronized(errors) { errors += TorrentSourceError(provider.id, provider.name, it.message ?: it.toString()) } }
                }
            }
        }
        groupJobs.awaitAll()
    }

    othersJob.await()
    nyaaJob.await()

    val limit = request.limit
    val allSorted = sortTorrentResults(results, request)
    val picked = mutableListOf<TorrentSourceResult>()
    val used = mutableSetOf<Int>()

    // Phase 1 — reserve the top 2 results from each provider.
    val providerIds = allSorted.map { it.providerId }.distinct()
    for (pid in providerIds) {
        var count = 0
        var i = 0
        while (i < allSorted.size && picked.size < limit && count < 2) {
            if (i !in used && allSorted[i].providerId == pid) {
                picked += allSorted[i]; used += i; count++
            }
            i++
        }
    }
    // Phase 2 — fill with highest-scored.
    for (i in allSorted.indices) {
        if (picked.size >= limit) break
        if (i in used) continue
        picked += allSorted[i]
    }

    TorrentSearchOutcome(
        streams = picked.map(::resultToStream),
        results = picked,
        errors = errors,
    )
}
