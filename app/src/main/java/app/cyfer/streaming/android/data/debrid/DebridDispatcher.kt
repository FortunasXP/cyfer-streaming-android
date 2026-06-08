package app.cyfer.streaming.android.data.debrid

import android.content.Context
import app.cyfer.streaming.android.data.settings.AppSettings
import app.cyfer.streaming.android.data.torrent.engine.resolveLocalTorrent

/**
 * Tries each configured resolver in turn until one returns a playable
 * URL. Order: Real-Debrid → TorBox → on-device torrent engine.
 * First success wins; if all fail, the combined failure messages are
 * thrown so the UI can surface them.
 *
 * The on-device path is gated by [AppSettings.debridFallbackToLocalTorrent]
 * so users without local-torrent storage budget can opt out.
 */
suspend fun resolveStreamingUrl(
    context: Context,
    settings: AppSettings,
    payload: DebridResolveRequest,
): DebridResolvedMedia {
    val errors = mutableListOf<String>()

    if (settings.realDebridEnabled && settings.realDebridApiToken.isNotBlank()) {
        runCatching { resolveRealDebrid(settings.realDebridApiToken, payload) }
            .onSuccess { return it }
            .onFailure { errors += "Real-Debrid: ${it.message ?: it.toString()}" }
    }

    if (settings.torboxEnabled && settings.torboxApiToken.isNotBlank()) {
        runCatching { resolveTorBox(settings.torboxApiToken, payload) }
            .onSuccess { return it }
            .onFailure { errors += "TorBox: ${it.message ?: it.toString()}" }
    }

    if (settings.debridFallbackToLocalTorrent) {
        runCatching { resolveLocalTorrent(context, payload) }
            .onSuccess { return it }
            .onFailure { errors += "Local torrent: ${it.message ?: it.toString()}" }
    }

    if (errors.isEmpty()) {
        throw DebridException(
            "No streaming method available. Configure Real-Debrid / TorBox or enable local torrent fallback in Settings.",
        )
    }
    throw DebridException(errors.joinToString(" — "))
}

/**
 * Backwards-compatible debrid-only entrypoint — same as
 * [resolveStreamingUrl] but skips the on-device torrent engine. Useful
 * when the caller wants HTTP playback only (no local cache write).
 */
suspend fun resolveWithDebrid(
    settings: AppSettings,
    payload: DebridResolveRequest,
): DebridResolvedMedia {
    val errors = mutableListOf<String>()

    if (settings.realDebridEnabled && settings.realDebridApiToken.isNotBlank()) {
        runCatching { resolveRealDebrid(settings.realDebridApiToken, payload) }
            .onSuccess { return it }
            .onFailure { errors += "Real-Debrid: ${it.message ?: it.toString()}" }
    }

    if (settings.torboxEnabled && settings.torboxApiToken.isNotBlank()) {
        runCatching { resolveTorBox(settings.torboxApiToken, payload) }
            .onSuccess { return it }
            .onFailure { errors += "TorBox: ${it.message ?: it.toString()}" }
    }

    if (errors.isEmpty()) {
        throw DebridException(
            "No debrid service is configured. Add a Real-Debrid or TorBox token in Settings.",
        )
    }
    throw DebridException(errors.joinToString(" — "))
}
