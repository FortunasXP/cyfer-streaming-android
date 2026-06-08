package app.cyfer.streaming.android.data.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TorBox resolver — creates a torrent, polls until cached/ready, picks a
 * file, requests a download link. Returns a direct HTTPS URL.
 *
 * Mirrors `resolveTorBoxTorrent` from `src/lib/torbox.ts`.
 */

private const val TORBOX_API_BASE = "https://api.torbox.app/v1/api"
private const val POLL_INTERVAL_MS = 2_000L
private const val MIN_TIMEOUT_MS = 10_000L
private const val MAX_TIMEOUT_MS = 5L * 60_000L

private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private val TERMINAL_STATES = setOf("error", "magnet_error", "dead")

private fun normaliseTimeout(value: Long): Long = value.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

private suspend fun tbRequest(
    token: String,
    path: String,
    method: String = "GET",
    multipart: Map<String, String>? = null,
): JsonObject? = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url("$TORBOX_API_BASE$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    when (method) {
        "POST" -> {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                multipart?.forEach { (k, v) -> addFormDataPart(k, v) }
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
                (it["detail"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["error"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["message"] as? JsonPrimitive)?.contentOrNull
            } ?: "TorBox returned HTTP ${res.code}"
            throw DebridException(msg)
        }
        parsed
    }
}

private data class TbFile(val id: Int, val name: String, val size: Long)

private data class TbTorrentInfo(
    val id: Int,
    val name: String?,
    val size: Long?,
    val downloadState: String,
    val progress: Double?,
    val downloadFinished: Boolean,
    val downloadPresent: Boolean,
    val files: List<TbFile>,
)

private fun parseTorrent(data: JsonObject): TbTorrentInfo {
    val files = (data["files"] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        TbFile(
            id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
            name = (o["name"] as? JsonPrimitive)?.contentOrNull
                ?: (o["short_name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    } ?: emptyList()
    return TbTorrentInfo(
        id = (data["id"] as? JsonPrimitive)?.intOrNull ?: 0,
        name = (data["name"] as? JsonPrimitive)?.contentOrNull,
        size = (data["size"] as? JsonPrimitive)?.longOrNull,
        downloadState = (data["download_state"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        progress = (data["progress"] as? JsonPrimitive)?.doubleOrNull,
        downloadFinished = (data["download_finished"] as? JsonPrimitive)?.booleanOrNull ?: false,
        downloadPresent = (data["download_present"] as? JsonPrimitive)?.booleanOrNull ?: false,
        files = files,
    )
}

private suspend fun createTorrent(token: String, magnet: String): Int {
    val response = tbRequest(token, "/torrents/createtorrent", "POST", mapOf("magnet" to magnet))
    val data = response?.get("data") as? JsonObject
        ?: throw DebridException("TorBox did not return a torrent id.")
    val id = (data["torrent_id"] as? JsonPrimitive)?.intOrNull
        ?: (data["queued_id"] as? JsonPrimitive)?.intOrNull
        ?: throw DebridException("TorBox did not return a torrent id.")
    return id
}

private suspend fun getTorrentInfo(token: String, id: Int): TbTorrentInfo {
    val raw = tbRequest(token, "/torrents/mylist?id=$id")
        ?: throw DebridException("TorBox returned an empty mylist body.")
    val data = raw["data"] as? JsonObject ?: throw DebridException("TorBox returned no torrent data.")
    return parseTorrent(data)
}

private suspend fun requestDownloadLink(token: String, torrentId: Int, fileId: Int): String {
    val urlPath = "/torrents/requestdl?token=${java.net.URLEncoder.encode(token, "UTF-8")}&torrent_id=$torrentId&file_id=$fileId"
    val raw = tbRequest(token, urlPath)
        ?: throw DebridException("TorBox returned an empty requestdl body.")
    return (raw["data"] as? JsonPrimitive)?.contentOrNull
        ?: throw DebridException("TorBox did not return a download URL.")
}

private fun isReady(info: TbTorrentInfo): Boolean {
    if (info.downloadFinished && info.downloadPresent) return true
    val state = info.downloadState.lowercase()
    return state == "completed" || state == "cached" || state == "uploading"
}

private suspend fun pollInfo(
    token: String,
    id: Int,
    startedAt: Long,
    timeoutMs: Long,
    ready: (TbTorrentInfo) -> Boolean,
): TbTorrentInfo {
    var latest = getTorrentInfo(token, id)
    while (!ready(latest)) {
        if (latest.downloadState.lowercase() in TERMINAL_STATES) {
            throw DebridException("TorBox torrent failed: ${latest.downloadState}")
        }
        if (System.currentTimeMillis() - startedAt > timeoutMs) {
            val pct = latest.progress?.let { " (${(it * 100).toInt()}%)" }.orEmpty()
            throw DebridException("TorBox torrent is not ready yet: ${latest.downloadState.ifBlank { "pending" }}$pct")
        }
        delay(POLL_INTERVAL_MS)
        latest = getTorrentInfo(token, id)
    }
    return latest
}

private fun TbFile.toPlayable(): PlayableFile = PlayableFile(id = id, name = name, sizeBytes = size)

suspend fun resolveTorBox(token: String, payload: DebridResolveRequest): DebridResolvedMedia {
    val source = payload.source.trim()
    if (!source.startsWith("magnet:", ignoreCase = true)) {
        throw DebridException("TorBox resolver requires a magnet link.")
    }
    if (token.isBlank()) throw DebridException("TorBox API token is not configured.")
    val timeoutMs = normaliseTimeout(payload.timeoutMs)
    val startedAt = System.currentTimeMillis()

    val torrentId = createTorrent(token, source)

    var info = pollInfo(token, torrentId, startedAt, timeoutMs) { it.files.isNotEmpty() || isReady(it) }

    val selectedTb = choosePlayableFile(info.files.map { it.toPlayable() }, payload)
        ?.let { picked -> info.files.firstOrNull { it.id == picked.id } }
        ?: throw DebridException("TorBox could not find a playable video file in this torrent.")

    if (!isReady(info)) {
        info = pollInfo(token, torrentId, startedAt, timeoutMs, ::isReady)
    }

    val url = requestDownloadLink(token, torrentId, selectedTb.id)

    return DebridResolvedMedia(
        url = url,
        filename = selectedTb.name.ifBlank { info.name },
        torrentId = torrentId.toString(),
        status = info.downloadState.ifBlank { "completed" },
        progress = info.progress?.let { (it * 100).toInt() },
        bytes = selectedTb.size.takeIf { it > 0 } ?: info.size,
    )
}
