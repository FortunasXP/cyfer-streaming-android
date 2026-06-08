package app.cyfer.streaming.android.data.kitsu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "KitsuRepo"
private const val KITSU_BASE = "https://kitsu.io/api/edge"

/**
 * Talks to Kitsu directly — no proxy needed, the public REST API is
 * CORS-open and unauthenticated. Mirrors the desktop's
 * `/api/kitsu?path=home|catalog|anime|episodes` route.
 *
 * We parse via kotlinx.serialization JSON (loose mode) rather than
 * Retrofit data classes because Kitsu's `attributes` blob is loosely
 * typed and JSON:API's `included` array is keyed by string id — much
 * cleaner to walk by hand.
 */
object KitsuRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getHome(): AnimeHomeFeed = coroutineScope {
        val spotlightJob = async { fetchList("/anime?include=categories&sort=-userCount&page[limit]=5") }
        val trendingJob = async { fetchList("/trending/anime?include=categories&page[limit]=10") }
        val airingJob = async { fetchList("/anime?include=categories&filter[status]=current&sort=-userCount&page[limit]=12") }
        val popularJob = async { fetchList("/anime?include=categories&sort=-userCount&page[limit]=20") }
        val popular = popularJob.await()
        val topRated = popular
            .filter { it.voteAverage != null }
            .sortedByDescending { it.voteAverage ?: 0f }
            .take(12)
        AnimeHomeFeed(
            spotlight = spotlightJob.await(),
            trending = trendingJob.await(),
            airing = airingJob.await(),
            topRated = topRated,
        )
    }

    /** Pull a single anime by Kitsu id — used by the detail screen. */
    suspend fun getAnime(kitsuId: String): AnimeTitle? {
        val payload = fetchJson("/anime/${kitsuId}?include=categories") ?: return null
        val included = (payload["included"] as? JsonArray).orEmpty()
        val data = payload["data"] as? JsonObject ?: return null
        return normaliseAnime(data, included)
    }

    /**
     * Episode list for a Kitsu anime id. Walks pages until we have them
     * all (Kitsu paginates at 20). Returns episodes sorted by
     * `seasonNumber, episodeNumber`. Skips episodes with episodeNumber=0
     * (typically OVAs / specials that show up muddled).
     */
    suspend fun getEpisodes(kitsuId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        val all = mutableListOf<AnimeEpisode>()
        var nextPath: String? = "/anime/${kitsuId}/episodes?page[limit]=20&sort=number"
        var safety = 80
        while (nextPath != null && safety-- > 0) {
            val payload = fetchJson(nextPath) ?: break
            val data = (payload["data"] as? JsonArray).orEmpty()
            data.mapNotNull { (it as? JsonObject)?.let(::normaliseEpisode) }.forEach { all += it }
            val link = ((payload["links"] as? JsonObject)?.get("next") as? JsonPrimitive)?.contentOrNull
            nextPath = link?.let { full ->
                runCatching {
                    val url = java.net.URL(full)
                    val pathFromEdge = url.path.removePrefix("/api/edge")
                    if (url.query.isNullOrEmpty()) pathFromEdge else "$pathFromEdge?${url.query}"
                }.getOrNull()
            }
        }
        all.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    }

    private fun normaliseEpisode(resource: JsonObject): AnimeEpisode? {
        val id = (resource["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val attrs = resource["attributes"] as? JsonObject ?: return null
        val episodeNumber = ((attrs["relativeNumber"] as? JsonPrimitive)?.intOrNull
            ?: (attrs["number"] as? JsonPrimitive)?.intOrNull
            ?: 0).coerceAtLeast(1)
        val seasonNumber = ((attrs["seasonNumber"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1)
        val titles = attrs["titles"] as? JsonObject
        val name = (attrs["canonicalTitle"] as? JsonPrimitive)?.contentOrNull
            ?: (titles?.get("en") as? JsonPrimitive)?.contentOrNull
            ?: (titles?.get("en_jp") as? JsonPrimitive)?.contentOrNull
            ?: "Episode $episodeNumber"
        val still = pickImage(attrs["thumbnail"], listOf("original", "large", "medium"))
        val air = (attrs["airdate"] as? JsonPrimitive)?.contentOrNull
            ?: (attrs["airDate"] as? JsonPrimitive)?.contentOrNull
        val runtime = (attrs["length"] as? JsonPrimitive)?.intOrNull
        return AnimeEpisode(
            id = id,
            episodeNumber = episodeNumber,
            seasonNumber = seasonNumber,
            name = name,
            overview = (attrs["synopsis"] as? JsonPrimitive)?.contentOrNull
                ?: (attrs["description"] as? JsonPrimitive)?.contentOrNull
                ?: "",
            stillUrl = still,
            airDate = air,
            runtime = runtime,
        )
    }

    suspend fun discover(
        page: Int,
        pageSize: Int = 20,
        query: String? = null,
        year: String? = null,
        season: String? = null,
        status: String? = null,
        genre: String? = null,
        sort: String = "Top rated",
    ): AnimeCatalogResult {
        val params = buildList {
            add("include" to "categories")
            add("page[limit]" to pageSize.toString())
            add("page[offset]" to (page * pageSize).toString())
            if (!query.isNullOrBlank()) add("filter[text]" to query)
            if (!year.isNullOrBlank() && year != "All") add("filter[seasonYear]" to year)
            if (!season.isNullOrBlank() && season != "All") add("filter[season]" to season.lowercase())
            if (!status.isNullOrBlank() && status != "All") {
                add("filter[status]" to if (status == "Airing") "current" else "finished")
            }
            if (!genre.isNullOrBlank() && genre != "All") {
                add("filter[categories]" to genre.lowercase().replace(' ', '-'))
            }
            val sortValue = when (sort) {
                "Newest" -> "-startDate"
                "A-Z" -> "canonicalTitle"
                "Episodes" -> "-episodeCount"
                else -> "-averageRating"
            }
            add("sort" to sortValue)
        }
        val q = params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        val payload = fetchJson("/anime?$q") ?: return AnimeCatalogResult()
        val included = (payload["included"] as? JsonArray).orEmpty()
        val items = (payload["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { normaliseAnime(it, included) }
        }
        val total = ((payload["meta"] as? JsonObject)?.get("count") as? JsonPrimitive)?.intOrNull
            ?: items.size
        val hasMore = ((payload["links"] as? JsonObject)?.get("next") as? JsonPrimitive)?.contentOrNull != null
        return AnimeCatalogResult(items = items, total = total, hasMore = hasMore)
    }

    // ── helpers ────────────────────────────────────────────────

    private suspend fun fetchList(path: String): List<AnimeTitle> {
        val payload = fetchJson(path) ?: return emptyList()
        val included = (payload["included"] as? JsonArray).orEmpty()
        return (payload["data"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { obj -> normaliseAnime(obj, included) } }
    }

    private suspend fun fetchJson(path: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$KITSU_BASE$path")
                .header("Accept", "application/vnd.api+json")
                .header("Content-Type", "application/vnd.api+json")
                .header("User-Agent", "CyferStreaming/0.3")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "Kitsu ${res.code} for $path")
                    return@use null
                }
                val body = res.body?.string().orEmpty()
                if (body.isBlank()) null
                else json.parseToJsonElement(body) as? JsonObject
            }
        }.onFailure { Log.w(TAG, "Kitsu fetch failed for $path", it) }.getOrNull()
    }

    private fun normaliseAnime(resource: JsonObject, included: List<kotlinx.serialization.json.JsonElement>): AnimeTitle? {
        val id = (resource["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val kitsuId = (resource["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val attrs = resource["attributes"] as? JsonObject ?: return null

        val titles = attrs["titles"] as? JsonObject
        val canonical = (attrs["canonicalTitle"] as? JsonPrimitive)?.contentOrNull
        val english = (titles?.get("en") as? JsonPrimitive)?.contentOrNull
        val romaji = (titles?.get("en_jp") as? JsonPrimitive)?.contentOrNull
        val japanese = (titles?.get("ja_jp") as? JsonPrimitive)?.contentOrNull
        val title = canonical ?: english ?: romaji ?: japanese ?: "Untitled"

        val original = japanese
        val startDate = (attrs["startDate"] as? JsonPrimitive)?.contentOrNull
        val averageRaw = (attrs["averageRating"] as? JsonPrimitive)?.doubleOrNull
        val average = averageRaw?.let { (it / 10f).toFloat() }?.let { (it * 10).toInt() / 10f }
        val subtype = (attrs["subtype"] as? JsonPrimitive)?.contentOrNull
        val statusRaw = (attrs["status"] as? JsonPrimitive)?.contentOrNull
        val status = when (statusRaw) {
            "current" -> "Airing"
            "finished" -> "Finished"
            else -> statusRaw
        }

        return AnimeTitle(
            id = id,
            kitsuId = kitsuId,
            title = title,
            englishTitle = english,
            romajiTitle = romaji,
            originalTitle = original,
            overview = (attrs["synopsis"] as? JsonPrimitive)?.contentOrNull
                ?: (attrs["description"] as? JsonPrimitive)?.contentOrNull
                ?: "",
            posterUrl = pickImage(attrs["posterImage"], listOf("original", "large", "medium", "small")),
            backdropUrl = pickImage(attrs["coverImage"], listOf("original", "large", "small"))
                ?: pickImage(attrs["posterImage"], listOf("original", "large")),
            releaseDate = startDate,
            year = startDate?.take(4),
            voteAverage = average,
            popularity = (attrs["userCount"] as? JsonPrimitive)?.intOrNull,
            genres = genresFor(resource, included),
            adult = (attrs["nsfw"] as? JsonPrimitive)?.booleanOrNull == true,
            episodeCount = (attrs["episodeCount"] as? JsonPrimitive)?.intOrNull,
            episodeLength = (attrs["episodeLength"] as? JsonPrimitive)?.intOrNull,
            status = status,
            subtype = subtype,
            season = seasonFromDate(startDate),
            animeKind = if (subtype?.lowercase() == "movie") "movie" else "series",
        )
    }

    private fun pickImage(node: kotlinx.serialization.json.JsonElement?, preferred: List<String>): String? {
        val obj = node as? JsonObject ?: return null
        for (k in preferred) {
            val value = (obj[k] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun genresFor(resource: JsonObject, included: List<kotlinx.serialization.json.JsonElement>): List<String> {
        val rels = resource["relationships"] as? JsonObject ?: return emptyList()
        val cats = rels["categories"] as? JsonObject ?: return emptyList()
        val data = cats["data"] as? JsonArray ?: return emptyList()
        val ids = data.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }.toSet()
        if (ids.isEmpty()) return emptyList()
        return included.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { ((it["type"] as? JsonPrimitive)?.contentOrNull) == "categories" }
            .filter { ((it["id"] as? JsonPrimitive)?.contentOrNull) in ids }
            .mapNotNull { obj ->
                val attrs = obj["attributes"] as? JsonObject ?: return@mapNotNull null
                (attrs["title"] as? JsonPrimitive)?.contentOrNull
                    ?: (attrs["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (attrs["slug"] as? JsonPrimitive)?.contentOrNull
            }
            .toList()
            .take(5)
    }

    private fun seasonFromDate(date: String?): String? {
        if (date == null || date.length < 7) return null
        val month = date.substring(5, 7).toIntOrNull() ?: return null
        return when {
            month <= 3 -> "Winter"
            month <= 6 -> "Spring"
            month <= 9 -> "Summer"
            else -> "Fall"
        }
    }
}
