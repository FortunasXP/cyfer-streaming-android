package app.cyfer.streaming.android.data.torrent.engine

import android.content.Context
import android.util.Log
import app.cyfer.streaming.android.data.debrid.DebridException
import app.cyfer.streaming.android.data.debrid.DebridResolveRequest
import app.cyfer.streaming.android.data.debrid.DebridResolvedMedia
import app.cyfer.streaming.android.data.debrid.PlayableFile
import app.cyfer.streaming.android.data.debrid.choosePlayableFile

/**
 * Public façade for the on-device torrent engine.
 *
 * Given a magnet URI, this:
 *  1. Starts the [TorrentForegroundService] (which boots the engine)
 *  2. Adds the magnet, waits for metadata
 *  3. Picks the right video file (re-using the debrid file picker so
 *     episode/title hints from the source picker apply the same way)
 *  4. Mounts it in the [TorrentStreamingServer]
 *  5. Returns a [DebridResolvedMedia] pointing at the local HTTP URL —
 *     same return type as the RD/TorBox resolvers, so the dispatcher
 *     can treat all three uniformly.
 */
suspend fun resolveLocalTorrent(
    context: Context,
    payload: DebridResolveRequest,
): DebridResolvedMedia {
    val source = payload.source.trim()
    if (!source.startsWith("magnet:", ignoreCase = true)) {
        throw DebridException("Local torrent resolver requires a magnet link.")
    }

    // Start the engine synchronously — the foreground service exists to
    // keep it alive during backgrounding, not to bootstrap it. (Service
    // onCreate runs on the main looper which we'd be blocking right now.)
    TorrentEngine.start(context.applicationContext)
    TorrentForegroundService.start(context, payload.title)

    val session = TorrentEngine.addMagnet(source)
    Log.i(TAG, "Added magnet ${session.infoHash}; awaiting metadata…")

    if (!session.awaitMetadata(timeoutMs = payload.timeoutMs.coerceAtLeast(10_000L))) {
        throw DebridException("Torrent metadata not received in time — swarm may be dead.")
    }

    val files = session.files()
    if (files.isEmpty()) throw DebridException("Torrent contains no files.")

    val playableFiles = files.map { f ->
        PlayableFile(id = f.index, name = f.relativePath, sizeBytes = f.sizeBytes)
    }
    val pickedPlayable = choosePlayableFile(playableFiles, payload)
        ?: throw DebridException("No playable video file found in this torrent.")
    val pickedFile = files.first { it.index == pickedPlayable.id }
    Log.i(TAG, "Picked file: ${pickedFile.relativePath} (${pickedFile.sizeBytes} bytes)")

    val server = TorrentStreamingServer.shared()
    server.mount(session, pickedFile)
    val url = server.streamUrl
        ?: throw DebridException("Streaming server failed to bind a port.")

    return DebridResolvedMedia(
        url = url,
        filename = pickedFile.relativePath.substringAfterLast('/'),
        torrentId = session.infoHash,
        status = "streaming",
        progress = null,
        bytes = pickedFile.sizeBytes,
    )
}

private const val TAG = "LocalTorrentResolver"
