package app.cyfer.streaming.android.data.torrent.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Vectors
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide wrapper around libtorrent4j's [SessionManager].
 *
 * The engine keeps a single session alive for the lifetime of the
 * [app.cyfer.streaming.android.data.torrent.engine.TorrentForegroundService].
 * Torrents are added by magnet URI; once metadata is in we hand out a
 * [TorrentSession] the caller can ask for files and open an InputStream
 * against — backed by [TorrentFileInputStream], which prioritizes pieces
 * on demand so HTTP Range reads from mpv don't stall the whole file.
 */
object TorrentEngine {

    private const val TAG = "TorrentEngine"
    private const val DEFAULT_PIECE_DEADLINE_MS = 1500
    private const val PIECE_WAIT_TIMEOUT_MS = 90_000L
    private const val PIECE_POLL_MS = 50L

    @Volatile private var session: SessionManager? = null
    private val sessions = ConcurrentHashMap<String, TorrentSession>()
    private var cacheRoot: File? = null
    /** Info-hashes the user has pinned for offline keep. Survives across
     *  player close — `stopAllActive()` skips these. */
    private val pinned: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Synchronized
    fun start(context: Context) {
        if (session != null) return
        val sm = SessionManager()
        // SessionParams with defaults is enough for streaming — we'll
        // tune piece selection per-handle.
        sm.start(SessionParams())
        sm.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.METADATA_RECEIVED -> {
                        Log.i(TAG, "Metadata received: ${alert.message()}")
                        // Once metadata is in, ask libtorrent to write a
                        // fastresume record so future cold starts can
                        // skip the check-files pass.
                        runCatching {
                            val msg = alert.message()
                            // Best-effort: find handles whose torrentFile()
                            // is now non-null and trigger save_resume_data.
                            sessions.values.forEach { s ->
                                if (s.handle.torrentFile() != null) {
                                    runCatching { s.handle.saveResumeData() }
                                }
                            }
                            Unit
                        }
                    }
                    AlertType.TORRENT_FINISHED -> {
                        // Completed — persist resume data so we don't
                        // re-hash a fully-downloaded torrent next launch.
                        val ah = (alert as? TorrentFinishedAlert)?.handle()
                        ah?.let {
                            runCatching { it.saveResumeData() }
                            Log.i(TAG, "Torrent finished, requested save_resume_data")
                        }
                    }
                    AlertType.SAVE_RESUME_DATA -> {
                        // libtorrent has produced a fresh fastresume blob.
                        // Persist it next to the data files so a future
                        // start can skip check-files.
                        runCatching {
                            val srd = alert as SaveResumeDataAlert
                            val params: AddTorrentParams = srd.params()
                            val bytes = AddTorrentParams.writeResumeDataBuf(params)
                            val ih = srd.handle()?.infoHash()?.toHex()?.lowercase()
                                ?: return@runCatching
                            val dir = File(cacheRoot, ih)
                            if (!dir.exists()) dir.mkdirs()
                            File(dir, "$ih.fastresume").writeBytes(bytes)
                            Log.i(TAG, "Saved fastresume ($ih, ${bytes.size} bytes)")
                        }.onFailure {
                            Log.w(TAG, "Failed to write fastresume: ${it.message}")
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        Log.w(TAG, "Torrent error: ${alert.message()}")
                    }
                    else -> Unit
                }
            }
        })
        session = sm
        cacheRoot = File(context.cacheDir, "torrents").apply { mkdirs() }
        Log.i(TAG, "Session started; cache root=${cacheRoot?.absolutePath}")
    }

    @Synchronized
    fun stop() {
        sessions.values.forEach { runCatching { it.dispose() } }
        sessions.clear()
        session?.let {
            runCatching { it.stop() }
        }
        session = null
        Log.i(TAG, "Session stopped")
    }

    /**
     * Add a magnet to the session and return a [TorrentSession] handle.
     * Call [TorrentSession.awaitMetadata] before reading files — magnets
     * arrive without their file list and we must fetch the .torrent.
     */
    suspend fun addMagnet(magnet: String): TorrentSession {
        val sm = session ?: error("TorrentEngine.start() must be called before addMagnet")
        val infoHash = parseInfoHash(magnet)
            ?: error("Magnet did not contain a valid btih info-hash")
        sessions[infoHash]?.let { return it }

        val saveDir = File(cacheRoot, infoHash).apply { mkdirs() }

        // Fast path: if we wrote a fastresume on a previous run, load it
        // and re-attach the torrent without forcing libtorrent to
        // re-hash every piece. The blob carries the piece bitfield, so
        // libtorrent goes straight to "complete" (or near-complete) in
        // milliseconds instead of seconds-to-minutes on a phone.
        val resumeFile = File(saveDir, "$infoHash.fastresume")
        if (resumeFile.exists()) {
            val loaded = runCatching {
                val bv = Vectors.bytes2byte_vector(resumeFile.readBytes())
                val ec = error_code()
                val swigParams = libtorrent.read_resume_data_ex(bv, ec)
                if (ec.value() != 0 || swigParams == null) {
                    error("read_resume_data_ex failed (ec=${ec.value()})")
                }
                val params = AddTorrentParams(swigParams)
                params.setSavePath(saveDir.absolutePath)
                sm.swig().async_add_torrent(params.swig())
                true
            }.onFailure {
                Log.w(TAG, "Failed to load fastresume for $infoHash, falling back to magnet: ${it.message}")
                runCatching { resumeFile.delete() }
            }.getOrDefault(false)
            if (loaded) {
                Log.i(TAG, "Loaded fastresume for $infoHash")
            } else {
                sm.download(magnet, saveDir, TorrentFlags.AUTO_MANAGED)
            }
        } else {
            // libtorrent4j's magnet `download` requires a torrent_flags_t —
            // AUTO_MANAGED leaves session management to libtorrent (the
            // sensible default for streaming).
            sm.download(magnet, saveDir, TorrentFlags.AUTO_MANAGED)
        }

        // Wait briefly for the handle to materialise — jlibtorrent needs
        // a moment after `download()` before find() returns non-null.
        val hash = Sha1Hash.parseHex(infoHash)
        val handle = withTimeoutOrNull(10_000L) {
            var h: TorrentHandle? = null
            while (h == null || !h.isValid) {
                h = sm.find(hash)
                if (h == null || !h.isValid) delay(100)
            }
            h
        } ?: error("Torrent handle never became valid for $infoHash")

        val s = TorrentSession(handle, saveDir, this, infoHash)
        sessions[infoHash] = s
        return s
    }

    /** Ask libtorrent to dump fastresume data for this torrent. The
     *  resulting blob lands as a SAVE_RESUME_DATA alert, which the
     *  listener persists to <infoHash>.fastresume. Called periodically
     *  by the DownloadsCoordinator poller so we never lose more than ~30 s
     *  of progress on an unclean kill. */
    fun requestSaveResume(infoHash: String) {
        val s = sessions[infoHash] ?: return
        runCatching { s.handle.saveResumeData() }
    }

    /** Save resume data for every active pinned torrent. Called on
     *  graceful tear-down so a normal app exit always leaves valid
     *  fastresume files behind. */
    fun saveAllResumeData() {
        sessions.values.forEach { s ->
            runCatching { s.handle.saveResumeData() }
        }
    }

    fun forget(infoHash: String) {
        sessions.remove(infoHash)?.let { runCatching { it.dispose() } }
    }

    /**
     * Tear down every active torrent — pause, remove from the session,
     * and forget the [TorrentSession]. Called when the player closes
     * so we're not silently chewing through the user's data plan.
     *
     * Pinned torrents are kept alive — that's the whole point of the
     * Downloads feature.
     */
    fun stopAllActive() {
        val sm = session ?: return
        sessions.values.toList().forEach { s ->
            if (s.infoHash in pinned) {
                Log.i(TAG, "Keeping pinned torrent ${s.infoHash} alive")
                return@forEach
            }
            runCatching {
                s.handle.pause()
                sm.remove(s.handle)
            }
            sessions.remove(s.infoHash)
        }
    }

    internal fun activeCount(): Int = sessions.size

    // ─────────────────────────── Pin / unpin / control ───────────────────────────

    fun pin(infoHash: String) {
        pinned += infoHash
        // Make sure the pinned torrent isn't paused (re-pin after pause).
        sessions[infoHash]?.handle?.let { runCatching { it.resume() } }
    }

    fun unpin(infoHash: String) {
        pinned -= infoHash
    }

    fun isPinned(infoHash: String): Boolean = infoHash in pinned

    fun pause(infoHash: String): Boolean {
        val s = sessions[infoHash] ?: return false
        return runCatching {
            s.handle.pause()
            manuallyPaused += infoHash
            true
        }.getOrDefault(false)
    }

    fun resume(infoHash: String): Boolean {
        val s = sessions[infoHash] ?: return false
        return runCatching {
            s.handle.resume()
            manuallyPaused -= infoHash
            true
        }.getOrDefault(false)
    }

    /** Fully remove a torrent (delete files included). Used when the
     *  user taps Delete on a pinned download. */
    fun remove(infoHash: String, deleteFiles: Boolean = true) {
        pinned -= infoHash
        val sm = session ?: return
        sessions.remove(infoHash)?.let { s ->
            runCatching { sm.remove(s.handle) }
            if (deleteFiles) runCatching { s.saveDir.deleteRecursively() }
        }
    }

    /** Tracks user-initiated pauses so the snapshot reports the right
     *  status — libtorrent's internal pause state isn't trivially
     *  queryable from libtorrent4j without grovelling through flags. */
    private val manuallyPaused: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Snapshot the byte counts for a pinned torrent. Returns null if
     *  the torrent isn't currently in the session. */
    fun snapshot(infoHash: String): TorrentProgress? {
        val s = sessions[infoHash] ?: return null
        val status = runCatching { s.handle.status() }.getOrNull() ?: return null
        val ti = runCatching { s.handle.torrentFile() }.getOrNull()
        val totalSize = ti?.totalSize() ?: 0L
        val downloaded = status.totalDone()
        val progress = status.progress()
        val downloadRate = status.downloadRate()
        // libtorrent's state enum exposes CHECKING_FILES and
        // CHECKING_RESUME_DATA — both mean we're verifying pieces
        // libtorrent already has on disk, not pulling from peers. Surface
        // that to the UI so the user doesn't think we're re-downloading.
        val state = runCatching { status.state() }.getOrNull()
        val checking = state == TorrentStatus.State.CHECKING_FILES ||
            state == TorrentStatus.State.CHECKING_RESUME_DATA
        return TorrentProgress(
            infoHash = infoHash,
            downloadedBytes = downloaded,
            totalSizeBytes = totalSize,
            progress = progress,
            downloadRate = downloadRate,
            paused = infoHash in manuallyPaused,
            seeds = status.numSeeds(),
            peers = status.numPeers(),
            checking = checking,
        )
    }

    data class TorrentProgress(
        val infoHash: String,
        val downloadedBytes: Long,
        val totalSizeBytes: Long,
        /** 0.0 .. 1.0 — libtorrent's reported progress. */
        val progress: Float,
        val downloadRate: Int,
        val paused: Boolean,
        val seeds: Int,
        val peers: Int,
        /** True while libtorrent is checking_files / checking_resume_data /
         *  allocating — i.e. verifying pieces, not downloading them. */
        val checking: Boolean = false,
    )

    /** btih hex string from a magnet URI, lower-cased, or null. */
    private fun parseInfoHash(magnet: String): String? {
        val m = Regex("btih:([A-Fa-f0-9]{40}|[A-Z2-7]{32})").find(magnet) ?: return null
        val raw = m.groupValues[1]
        // base32 → hex if needed (libtorrent4j accepts hex everywhere).
        return if (raw.length == 32) base32ToHex(raw) else raw.lowercase()
    }

    private fun base32ToHex(b32: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (c in b32.uppercase()) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) continue
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format((value shr bits) and 0xFF))
                value = value and ((1 shl bits) - 1)
            }
        }
        return out.toString()
    }
}

/**
 * One torrent's lifecycle handle. Created via [TorrentEngine.addMagnet].
 *
 * - [awaitMetadata] suspends until libtorrent has the .torrent
 * - [files] lists the contents
 * - [pickPrimaryVideo] returns the largest playable video file
 * - [setStreamingFile] tells libtorrent which pieces to prioritise
 * - [openStream] hands back an InputStream that blocks on missing pieces
 */
class TorrentSession internal constructor(
    val handle: TorrentHandle,
    val saveDir: File,
    private val engine: TorrentEngine,
    val infoHash: String,
) {
    data class TorrentFile(
        val index: Int,
        val relativePath: String,
        val sizeBytes: Long,
    )

    private val videoExtensions = setOf(
        ".mkv", ".mp4", ".m4v", ".mov", ".avi", ".webm", ".ts", ".m2ts",
        ".mts", ".wmv", ".flv", ".mpg", ".mpeg",
    )

    suspend fun awaitMetadata(timeoutMs: Long = 60_000L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (handle.torrentFile() == null) delay(200)
            true
        } ?: false

    fun files(): List<TorrentFile> {
        val ti = handle.torrentFile() ?: return emptyList()
        val files = ti.files()
        return (0 until files.numFiles()).map { idx ->
            TorrentFile(
                index = idx,
                relativePath = files.filePath(idx),
                sizeBytes = files.fileSize(idx),
            )
        }
    }

    /** Largest video file in the torrent, or null if none. */
    fun pickPrimaryVideo(): TorrentFile? {
        val list = files()
        return list
            .filter { f -> videoExtensions.any { f.relativePath.endsWith(it, ignoreCase = true) } }
            .maxByOrNull { it.sizeBytes }
            ?: list.maxByOrNull { it.sizeBytes }
    }

    /**
     * Tell libtorrent we're going to stream this file — boost its pieces.
     *
     * Critically, we *also* pre-prioritize the first ~16 pieces and the
     * last piece of the chosen file with TOP_PRIORITY + tight deadlines.
     * Without this the player's first `read()` is what triggers piece
     * scheduling, which adds 5–10 s of "Preparing stream" while the
     * swarm gets up to speed. Boosting up front lets pieces arrive while
     * MPV is still constructing its decoder.
     *
     * The last piece matters too — MKV/MP4 trailers carry the index, so
     * MPV typically issues a range request to the end before it can
     * decode the first frame.
     */
    fun setStreamingFile(fileIndex: Int) {
        val ti = handle.torrentFile() ?: return
        val numFiles = ti.files().numFiles()
        val files = ti.files()
        // Drop priority on every other file, keep ours at normal.
        for (i in 0 until numFiles) {
            handle.filePriority(i, if (i == fileIndex) Priority.DEFAULT else Priority.IGNORE)
        }
        // Compute the piece range that backs this file.
        val pieceLength = ti.pieceLength()
        val fileOffset = files.fileOffset(fileIndex)
        val fileSize = files.fileSize(fileIndex)
        if (pieceLength <= 0 || fileSize <= 0) return
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
        val totalPieces = ti.numPieces()

        // Front-load the first 16 pieces with TOP_PRIORITY + 200 ms deadlines.
        val frontEnd = minOf(firstPiece + 16, lastPiece)
        for (p in firstPiece..frontEnd) {
            if (p in 0 until totalPieces) {
                runCatching {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 200)
                }
            }
        }
        // Trailer — MKV/MP4 index lives at the tail. MPV WILL fetch it
        // before producing a frame.
        if (lastPiece in 0 until totalPieces && lastPiece > frontEnd) {
            runCatching {
                handle.piecePriority(lastPiece, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(lastPiece, 800)
            }
        }
        Log.i(
            "TorrentSession",
            "Pre-prioritised pieces $firstPiece..$frontEnd + $lastPiece for streaming",
        )
    }

    /** Absolute path of [file] inside the save directory. */
    fun fileOnDisk(file: TorrentFile): File =
        File(saveDir, file.relativePath)

    /**
     * Open an InputStream over the given file. Reads block on demand
     * until the relevant pieces have been downloaded (with a hard
     * timeout per piece to bail out if the swarm is dead).
     */
    fun openStream(file: TorrentFile): TorrentFileInputStream {
        val ti = handle.torrentFile() ?: error("Metadata not yet received")
        val files = ti.files()
        val fileOffsetInTorrent = files.fileOffset(file.index)
        return TorrentFileInputStream(
            handle = handle,
            file = file,
            diskPath = fileOnDisk(file),
            pieceLength = ti.pieceLength(),
            fileOffsetInTorrent = fileOffsetInTorrent,
        )
    }

    fun dispose() {
        runCatching { handle.pause() }
        // We deliberately don't remove the torrent — letting libtorrent
        // keep seeding cached files is harmless and useful for the swarm.
    }
}

/**
 * Blocking InputStream that walks across pieces of a torrent file, asking
 * libtorrent to prioritise + finish each piece before returning bytes.
 *
 * Designed for the NanoHTTPD streaming response — mpv will issue Range
 * GETs as it seeks, and each new TorrentFileInputStream targets the byte
 * range the player asked for.
 */
class TorrentFileInputStream(
    private val handle: TorrentHandle,
    private val file: TorrentSession.TorrentFile,
    private val diskPath: File,
    private val pieceLength: Int,
    private val fileOffsetInTorrent: Long,
) : java.io.InputStream() {

    @Volatile private var position: Long = 0L
    @Volatile private var closed: Boolean = false
    private val totalLength: Long = file.sizeBytes

    /** Seek before the first read, e.g. for HTTP Range requests. */
    fun seek(offset: Long) {
        require(offset in 0..totalLength) { "seek($offset) out of bounds [$totalLength]" }
        position = offset
    }

    override fun available(): Int = (totalLength - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun read(): Int {
        val buf = ByteArray(1)
        return if (read(buf, 0, 1) <= 0) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (position >= totalLength) return -1
        val toRead = minOf(len.toLong(), totalLength - position).toInt()
        if (toRead <= 0) return -1

        val absoluteOffset = fileOffsetInTorrent + position
        val firstPiece = (absoluteOffset / pieceLength).toInt()
        val lastPiece = ((absoluteOffset + toRead - 1) / pieceLength).toInt()
        waitForPieces(firstPiece, lastPiece)

        // Pre-fetch a few pieces ahead so we don't stall on every read.
        val prefetchEnd = minOf(lastPiece + 4, ((fileOffsetInTorrent + totalLength - 1) / pieceLength).toInt())
        for (p in (lastPiece + 1)..prefetchEnd) {
            handle.piecePriority(p, Priority.SIX)
            runCatching { handle.setPieceDeadline(p, 3000) }
        }

        if (!diskPath.exists()) {
            // libtorrent may not have created the file yet; pieces could
            // be in flight but the file empty. Spin briefly.
            val deadline = System.currentTimeMillis() + 5_000L
            while (!diskPath.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (!diskPath.exists()) return 0
        }

        val raf = RandomAccessFile(diskPath, "r")
        try {
            raf.seek(position)
            val read = raf.read(b, off, toRead)
            if (read > 0) position += read
            return read
        } finally {
            raf.close()
        }
    }

    override fun close() {
        closed = true
        super.close()
    }

    private fun waitForPieces(firstPiece: Int, lastPiece: Int) {
        for (p in firstPiece..lastPiece) {
            if (handle.havePiece(p)) continue
            // Boost priority + deadline so libtorrent picks this up fast.
            handle.piecePriority(p, Priority.TOP_PRIORITY)
            runCatching { handle.setPieceDeadline(p, 500) }
            val deadline = System.currentTimeMillis() + 90_000L  // PIECE_WAIT_TIMEOUT_MS
            while (!handle.havePiece(p)) {
                if (System.currentTimeMillis() > deadline)
                    throw java.io.IOException("Timed out waiting for piece $p")
                if (closed) throw java.io.IOException("Stream closed while waiting for piece $p")
                Thread.sleep(50)
            }
        }
    }
}
