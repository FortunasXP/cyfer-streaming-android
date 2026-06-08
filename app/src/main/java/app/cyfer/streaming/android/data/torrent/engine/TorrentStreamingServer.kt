package app.cyfer.streaming.android.data.torrent.engine

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Tiny in-process HTTP server fronting a single active torrent file.
 *
 * mpv hits `http://127.0.0.1:<port>/stream` and gets the largest video
 * file from whichever [TorrentSession] is currently mounted. Range
 * requests are honoured so the player can seek inside a partial download.
 *
 * One server runs for the lifetime of the
 * [TorrentForegroundService]; the mounted torrent can be swapped with
 * [mount].
 */
class TorrentStreamingServer(port: Int = 0) : NanoHTTPD("127.0.0.1", port) {

    private val mounted = AtomicReference<Mount?>(null)

    private data class Mount(
        val session: TorrentSession,
        val file: TorrentSession.TorrentFile,
        val mimeType: String,
    )

    /** Currently-streaming file's URL, or null if nothing mounted. */
    val streamUrl: String?
        get() = if (mounted.get() != null && listeningPort > 0)
            "http://127.0.0.1:$listeningPort/stream"
        else null

    fun mount(session: TorrentSession, file: TorrentSession.TorrentFile) {
        val mime = guessMime(file.relativePath)
        session.setStreamingFile(file.index)
        mounted.set(Mount(session, file, mime))
        Log.i(TAG, "Mounted ${file.relativePath} (${file.sizeBytes} bytes) at $streamUrl")
    }

    fun unmount() {
        mounted.set(null)
    }

    override fun serve(session: IHTTPSession): Response {
        val mount = mounted.get()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No torrent mounted")
        if (session.uri != "/stream") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        val total = mount.file.sizeBytes
        val rangeHeader = session.headers["range"]?.takeIf { it.startsWith("bytes=", true) }
        val (start, end) = parseRange(rangeHeader, total)
        val length = end - start + 1

        Log.d(TAG, "Range request: bytes $start-$end / $total (length=$length)")

        val stream = runCatching {
            mount.session.openStream(mount.file).also { it.seek(start) }
        }.getOrElse {
            Log.w(TAG, "openStream failed", it)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, it.message ?: "error")
        }

        val status = if (rangeHeader == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val response = newFixedLengthResponse(status, mount.mimeType, stream, length)
        response.addHeader("Accept-Ranges", "bytes")
        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $start-$end/$total")
        }
        response.addHeader("Content-Length", length.toString())
        return response
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
        if (header == null) return 0L to (total - 1)
        return try {
            val spec = header.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            val start = spec.substring(0, dash).toLongOrNull() ?: 0L
            val endRaw = spec.substring(dash + 1).takeIf { it.isNotEmpty() }?.toLongOrNull()
            val end = endRaw?.coerceAtMost(total - 1) ?: (total - 1)
            start.coerceIn(0, total - 1) to end.coerceAtLeast(start)
        } catch (_: Throwable) {
            0L to (total - 1)
        }
    }

    private fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts", "mts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "TorrentStreamSrv"

        @Volatile private var shared: TorrentStreamingServer? = null

        @Synchronized
        fun shared(): TorrentStreamingServer {
            shared?.let { return it }
            val srv = TorrentStreamingServer(port = 0)
            srv.start(SOCKET_READ_TIMEOUT, /* daemon = */ false)
            shared = srv
            Log.i(TAG, "Streaming server started on port ${srv.listeningPort}")
            return srv
        }

        @Synchronized
        fun stopShared() {
            shared?.let {
                runCatching { it.stop() }
            }
            shared = null
        }
    }
}
