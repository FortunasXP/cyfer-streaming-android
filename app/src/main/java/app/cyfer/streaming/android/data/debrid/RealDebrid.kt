package app.cyfer.streaming.android.data.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Real-Debrid resolver — adds a magnet, polls until ready, picks the
 * right file, unrestricts the host link, returns a direct HTTPS URL.
 *
 * Mirrors `resolveRealDebridTorrent` from `src/lib/real-debrid.ts`.
 */

private const val REAL_DEBRID_API_BASE = "https://api.real-debrid.com/rest/1.0"
private const val POLL_INTERVAL_MS = 1_500L
private const val MIN_TIMEOUT_MS = 10_000L
private const val MAX_TIMEOUT_MS = 5L * 60_000L

private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private val TERMINAL_STATUSES = setOf("magnet_error", "error", "virus", "dead")

private fun normaliseTimeout(value: Long): Long = value.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

private suspend fun rdRequest(
    token: String,
    path: String,
    method: String = "GET",
    form: Map<String, String>? = null,
): JsonObject? = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url("$REAL_DEBRID_API_BASE$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    when (method) {
        "POST" -> {
            val body = FormBody.Builder().apply {
                form?.forEach { (k, v) -> add(k, v) }
            }.build()
            builder.post(body)
        }
        "GET" -> builder.get()
        else -> error("Unsupported method $method")
    }

    client.newCall(builder.build()).execute().use { res ->
        val text = res.body?.string().orEmpty()
        val parsed = runCatching {
            if (text.isBlank()) null else json.parseToJsonElement(text) as? JsonObject
        }.getOrNull()
        if (!res.isSuccessful) {
            val msg = parsed?.let {
                (it["error"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["message"] as? JsonPrimitive)?.contentOrNull
            } ?: "Real-Debrid returned HTTP ${res.code}"
            throw DebridException(msg)
        }
        parsed
    }
}

private suspend fun addMagnet(token: String, magnet: String): String {
    val response = rdRequest(token, "/torrents/addMagnet", "POST", mapOf("magnet" to magnet))
    return (response?.get("id") as? JsonPrimitive)?.contentOrNull
        ?: throw DebridException("Real-Debrid did not return a torrent id.")
}

private data class RdTorrentInfo(
    val id: String,
    val status: String,
    val progress: Int?,
    val bytes: Long?,
    val filename: String?,
    val links: List<String>,
    val files: List<RdFile>,
)

private data class RdFile(
    val id: Int,
    val path: String,
    val bytes: Long,
    val selected: Boolean,
)

private fun parseTorrentInfo(obj: JsonObject): RdTorrentInfo {
    val files = (obj["files"] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        RdFile(
            id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
            path = (o["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
            selected = ((o["selected"] as? JsonPrimitive)?.intOrNull ?: 0) == 1,
        )
    } ?: emptyList()
    val links = (obj["links"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    return RdTorrentInfo(
        id = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        status = (obj["status"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        progress = (obj["progress"] as? JsonPrimitive)?.intOrNull,
        bytes = (obj["bytes"] as? JsonPrimitive)?.longOrNull,
        filename = (obj["filename"] as? JsonPrimitive)?.contentOrNull,
        links = links,
        files = files,
    )
}

private suspend fun getTorrentInfo(token: String, id: String): RdTorrentInfo {
    val raw = rdRequest(token, "/torrents/info/${java.net.URLEncoder.encode(id, "UTF-8")}")
        ?: throw DebridException("Real-Debrid returned an empty torrent info body.")
    return parseTorrentInfo(raw)
}

private suspend fun selectFiles(token: String, id: String, fileIds: String) {
    rdRequest(token, "/torrents/selectFiles/${java.net.URLEncoder.encode(id, "UTF-8")}", "POST", mapOf("files" to fileIds))
}

private data class UnrestrictedLink(val download: String?, val filename: String?, val filesize: Long?)

private suspend fun unrestrictLink(token: String, link: String): UnrestrictedLink {
    val response = rdRequest(token, "/unrestrict/link", "POST", mapOf("link" to link))
        ?: throw DebridException("Real-Debrid returned an empty unrestrict body.")
    return UnrestrictedLink(
        download = (response["download"] as? JsonPrimitive)?.contentOrNull
            ?: (response["link"] as? JsonPrimitive)?.contentOrNull,
        filename = (response["filename"] as? JsonPrimitive)?.contentOrNull,
        filesize = (response["filesize"] as? JsonPrimitive)?.longOrNull,
    )
}

private suspend fun pollInfo(
    token: String,
    id: String,
    startedAt: Long,
    timeoutMs: Long,
    ready: (RdTorrentInfo) -> Boolean,
): RdTorrentInfo {
    var latest = getTorrentInfo(token, id)
    while (!ready(latest)) {
        if (latest.status.lowercase() in TERMINAL_STATUSES) {
            throw DebridException("Real-Debrid torrent failed: ${latest.status}")
        }
        if (System.currentTimeMillis() - startedAt > timeoutMs) {
            val pct = latest.progress?.let { " ($it%)" }.orEmpty()
            throw DebridException("Real-Debrid torrent is not ready yet: ${latest.status.ifBlank { "pending" }}$pct")
        }
        delay(POLL_INTERVAL_MS)
        latest = getTorrentInfo(token, id)
    }
    return latest
}

private fun chooseHostLink(info: RdTorrentInfo, selected: RdFile?): String? {
    val links = info.links.filter { it.isNotBlank() }
    if (links.isEmpty()) return null
    val selectedFiles = info.files.filter { it.selected }
    if (selected != null && selectedFiles.size == links.size) {
        val idx = selectedFiles.indexOfFirst { it.id == selected.id }
        if (idx >= 0 && idx < links.size) return links[idx]
    }
    return links.first()
}

private fun RdFile.toPlayable(): PlayableFile =
    PlayableFile(id = id, name = path.replace('\\', '/'), sizeBytes = bytes, isPreselected = selected)

suspend fun resolveRealDebrid(token: String, payload: DebridResolveRequest): DebridResolvedMedia {
    val source = payload.source.trim()
    if (!source.startsWith("magnet:", ignoreCase = true)) {
        throw DebridException("Real-Debrid resolver requires a magnet link.")
    }
    if (token.isBlank()) throw DebridException("Real-Debrid API token is not configured.")
    val timeoutMs = normaliseTimeout(payload.timeoutMs)
    val startedAt = System.currentTimeMillis()

    val torrentId = addMagnet(token, source)

    var info = pollInfo(token, torrentId, startedAt, timeoutMs) { item ->
        item.files.isNotEmpty() || (item.status == "downloaded" && item.links.isNotEmpty())
    }

    var selectedRd = choosePlayableFile(info.files.map { it.toPlayable() }, payload)
        ?.let { picked -> info.files.firstOrNull { it.id == picked.id } }

    if (selectedRd != null && info.links.isEmpty()) {
        selectFiles(token, torrentId, selectedRd.id.toString())
        info = pollInfo(token, torrentId, startedAt, timeoutMs) { it.status == "downloaded" && it.links.isNotEmpty() }
        selectedRd = choosePlayableFile(info.files.map { it.toPlayable() }, payload)
            ?.let { picked -> info.files.firstOrNull { it.id == picked.id } } ?: selectedRd
    } else if (selectedRd == null && info.links.isEmpty()) {
        throw DebridException("Real-Debrid could not find a playable video file in this torrent.")
    }

    if (info.status != "downloaded" || info.links.isEmpty()) {
        info = pollInfo(token, torrentId, startedAt, timeoutMs) { it.status == "downloaded" && it.links.isNotEmpty() }
    }

    val hostLink = chooseHostLink(info, selectedRd)
        ?: throw DebridException("Real-Debrid did not return a host link for the selected file.")
    val unrestricted = unrestrictLink(token, hostLink)
    val url = unrestricted.download
        ?: throw DebridException("Real-Debrid did not return a direct download URL.")

    return DebridResolvedMedia(
        url = url,
        filename = unrestricted.filename
            ?: selectedRd?.path?.removePrefix("/")
            ?: info.filename,
        torrentId = torrentId,
        status = info.status.ifBlank { "downloaded" },
        progress = info.progress,
        bytes = unrestricted.filesize ?: selectedRd?.bytes ?: info.bytes,
    )
}
