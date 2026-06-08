package app.cyfer.streaming.android.player

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class MpvTrack(
    val id: Int,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val selected: Boolean
)

data class MpvPlaybackState(
    val playing: Boolean = false,
    val paused: Boolean = true,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val audioTracks: List<MpvTrack> = emptyList(),
    val subtitleTracks: List<MpvTrack> = emptyList(),
    val currentAudioId: Int = 0,
    val currentSubId: Int = 0,
    val hwdec: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hdrDisplay: HdrDisplayCapabilities = HdrDisplayCapabilities(),
    val hdrVideo: HdrVideoMetadata = HdrVideoMetadata(),
    val idle: Boolean = true
)

/**
 * Singleton wrapper around the mpv-android-lib [MPV] instance.
 * Exposes a clean Kotlin API + observable [state] via StateFlow.
 */
object MpvPlayer : MPV.EventObserver {

    private const val TAG = "MpvPlayer"

    /** The underlying MPV instance — one per process. */
    val mpv = MPV()

    private val _state = MutableStateFlow(MpvPlaybackState())
    val state: StateFlow<MpvPlaybackState> = _state.asStateFlow()

    private var initialized = false
    private var currentHwdec: String = "mediacodec"

    fun initialize(context: Context) {
        if (initialized) return
        try {
            val hdrCaps = HdrDisplayDetector.detect(context)
            _state.update { it.copy(hdrDisplay = hdrCaps) }
            mpv.create(context)
            applyBaseOptions(hdrCaps)
            mpv.addObserver(this)
            mpv.init()
            initialized = true
            Log.i(TAG, "MPV initialized successfully; display=${hdrCaps.label}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MPV", e)
        }
    }

    /**
     * Apply a new hwdec mode at runtime. Safe to call between loads —
     * MPV reconfigures the decoder when [hwdec] changes. Passing the
     * same mode is a no-op.
     */
    fun setHardwareDecoding(mpvHwdecValue: String) {
        if (mpvHwdecValue == currentHwdec) return
        currentHwdec = mpvHwdecValue
        if (initialized) {
            Log.i(TAG, "Switching hwdec → $mpvHwdecValue")
            setOption("hwdec", mpvHwdecValue)
        }
    }

    fun refreshHdrCapabilities(context: Context): HdrDisplayCapabilities {
        val caps = HdrDisplayDetector.detect(context)
        _state.update { it.copy(hdrDisplay = caps) }
        if (initialized) applyHdrOptions(caps)
        return caps
    }

    private fun applyBaseOptions(hdrCaps: HdrDisplayCapabilities) {
        setOption("vo", "gpu")
        setOption("gpu-context", "android")
        // Honour any user override applied via setHardwareDecoding before
        // initialize(); falls back to the mediacodec default otherwise.
        setOption("hwdec", currentHwdec)
        setOption("ao", "audiotrack")
        setOption("input-default-bindings", "yes")
        setOption("keep-open", "yes")
        setOption("save-position-on-quit", "no")
        setOption("force-window", "no")

        // ── Streaming-first cache tuning ─────────────────────────────
        // MPV defaults assume a big local file: 75 MB ringbuffer, 5 s of
        // demuxer readahead, and "pause on first buffer underrun". For
        // torrent / debrid playback that translates into "spin for ages
        // before the first frame appears" — we'd rather start fast and
        // accept the occasional bufferbar.
        setOption("cache", "yes")
        setOption("cache-secs", "10")            // 10 s of stream cached
        setOption("demuxer-readahead-secs", "1") // header parse: ~1 s
        setOption("cache-pause-initial", "no")   // don't gate startup on full cache
        setOption("cache-pause", "no")           // never auto-pause for buffering
        setOption("network-timeout", "30")       // bail at 30 s — beyond that the source is dead
        setOption("hr-seek", "yes")

        applyHdrOptions(hdrCaps)
    }

    private fun applyHdrOptions(hdrCaps: HdrDisplayCapabilities) {
        setOption("hdr-compute-peak", "auto")
        setOption("tone-mapping", "auto")
        setOption("gamut-mapping-mode", "auto")

        if (hdrCaps.hdrCapable) {
            setOption("target-colorspace-hint", "yes")
            setOption("target-colorspace-hint-mode", "source-dynamic")
            setOption("target-prim", "auto")
            setOption("target-trc", "auto")
            setOption("target-peak", "auto")
        } else {
            setOption("target-colorspace-hint", "no")
            setOption("target-prim", "bt.709")
            setOption("target-trc", "gamma2.2")
            setOption("target-peak", "100")
        }
    }

    private fun setOption(name: String, value: String) {
        runCatching { mpv.setOptionString(name, value) }
            .onFailure { Log.w(TAG, "MPV option rejected: $name=$value (${it.message})") }
    }

    fun attachSurface(surface: Surface) {
        if (!initialized) return
        runCatching { mpv.attachSurface(surface) }
            .onFailure { Log.w(TAG, "Failed to attach MPV surface", it) }
    }

    fun detachSurface() {
        if (!initialized) return
        runCatching { mpv.detachSurface() }
            .onFailure { Log.w(TAG, "Failed to detach MPV surface", it) }
    }

    fun setForceWindow(enabled: Boolean) {
        if (!initialized) return
        setOption("force-window", if (enabled) "yes" else "no")
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyString("android-surface-size", "${width}x${height}") }
            .onFailure { Log.w(TAG, "Failed to update MPV surface size", it) }
    }

    fun loadUrl(url: String, title: String? = null) {
        if (!initialized) return
        // Reset state so any prior file's position/duration/tracks don't
        // bleed into the loading state of the new one — the player UI
        // uses position > 0 as its "first frame rendered" signal.
        _state.update {
            it.copy(
                idle = false,
                paused = false,
                position = 0.0,
                duration = 0.0,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                currentAudioId = 0,
                currentSubId = 0,
                hdrVideo = HdrVideoMetadata(),
            )
        }
        mpv.command("loadfile", url)
    }

    fun togglePause() {
        if (!initialized) return
        val paused = mpv.getPropertyBoolean("pause") ?: false
        mpv.setPropertyBoolean("pause", !paused)
    }

    fun play() {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", true)
    }

    fun seekTo(positionSec: Double) {
        if (!initialized) return
        mpv.command("seek", positionSec.toString(), "absolute")
    }

    fun seekRelative(seconds: Int) {
        if (!initialized) return
        mpv.command("seek", seconds.toString(), "relative")
    }

    fun setAudioTrack(id: Int) {
        if (!initialized) return
        mpv.setPropertyInt("aid", id)
    }

    fun setSubtitleTrack(id: Int) {
        if (!initialized) return
        mpv.setPropertyInt("sid", id)
    }

    /**
     * Add an external subtitle track and select it immediately. The URL
     * can be a direct HTTP(S) link to an .srt / .vtt / .ass file — MPV
     * fetches + parses it without our involvement.
     *
     *   sub-add <url> [<flags>] [<title>] [<lang>]
     *
     * `flags=select` makes it the active sub track right away.
     */
    fun loadExternalSubtitle(url: String, title: String? = null, lang: String? = null) {
        if (!initialized) return
        // mpv `sub-add` accepts URL inline space-separated. We quote the URL
        // and optional fields so mpv parses them as single tokens even when
        // they contain spaces (CDN URLs, label text).
        val parts = buildString {
            append("sub-add \"$url\" select")
            if (!title.isNullOrBlank()) append(" \"$title\"")
            if (!lang.isNullOrBlank()) append(" \"$lang\"")
        }
        runCatching { mpv.command(parts) }
            .onFailure { Log.w(TAG, "sub-add failed for $url: ${it.message}") }
    }

    fun setVolume(volume: Int) {
        if (!initialized) return
        mpv.setPropertyInt("volume", volume.coerceIn(0, 150))
    }

    /** Playback speed multiplier. 1.0 = normal. */
    fun setPlaybackSpeed(speed: Double) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("speed", speed.coerceIn(0.25, 4.0)) }
    }

    /**
     * Override the video aspect ratio. Empty string = "no override"
     * (default MPV behaviour, honours the stream's stated aspect).
     * Common values: "16:9", "4:3", "21:9", "1.85", "2.35".
     */
    fun setAspectRatio(aspect: String) {
        if (!initialized) return
        runCatching { mpv.setOptionString("video-aspect-override", aspect) }
    }

    /** Subtitle font scale. 1.0 = MPV default. 0.5..3.0 typical range. */
    fun setSubtitleScale(scale: Double) {
        if (!initialized) return
        runCatching { mpv.setPropertyDouble("sub-scale", scale.coerceIn(0.5, 3.0)) }
    }

    /**
     * Vertical subtitle position. 0 = top, 100 = bottom (MPV default ~100).
     */
    fun setSubtitlePosition(percent: Int) {
        if (!initialized) return
        runCatching { mpv.setPropertyInt("sub-pos", percent.coerceIn(0, 100)) }
    }

    /** Toggle a black 50% backdrop behind subtitles for legibility. */
    fun setSubtitleBackdrop(enabled: Boolean) {
        if (!initialized) return
        runCatching {
            mpv.setOptionString("sub-back-color", if (enabled) "0.0/0.0/0.0/0.5" else "0.0/0.0/0.0/0.0")
        }
    }

    fun stop() {
        if (!initialized) return
        mpv.command("stop")
        _state.update { MpvPlaybackState(hdrDisplay = it.hdrDisplay) }
    }

    fun destroy() {
        if (!initialized) return
        mpv.removeObserver(this)
        mpv.destroy()
        initialized = false
        _state.update { MpvPlaybackState(hdrDisplay = it.hdrDisplay) }
    }

    // ── MPV.EventObserver callbacks ─────────────────────────────

    override fun eventProperty(property: String) {
        // no-value property change
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> _state.update { it.copy(position = value.toDouble()) }
            "duration" -> _state.update { it.copy(duration = value.toDouble()) }
            "aid" -> _state.update { it.copy(currentAudioId = value.toInt()) }
            "sid" -> _state.update { it.copy(currentSubId = value.toInt()) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _state.update { it.copy(position = value) }
            "duration" -> _state.update { it.copy(duration = value) }
            "video-params/sig-peak" -> updateHdrVideo { it.copy(signalPeak = value) }
            "video-params/max-cll" -> updateHdrVideo { it.copy(maxCll = value) }
            "video-params/max-fall" -> updateHdrVideo { it.copy(maxFall = value) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _state.update { it.copy(paused = value, playing = !value) }
            "idle-active" -> _state.update { it.copy(idle = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "hwdec-current" -> _state.update { it.copy(hwdec = value) }
            "video-codec" -> _state.update { it.copy(videoCodec = value) }
            "audio-codec-name" -> _state.update { it.copy(audioCodec = value) }
            "video-params/primaries" -> updateHdrVideo { it.copy(primaries = value) }
            "video-params/gamma" -> updateHdrVideo { it.copy(transfer = value) }
            "video-params/light" -> updateHdrVideo { it.copy(light = value) }
            "video-params/colormatrix" -> updateHdrVideo { it.copy(colorMatrix = value) }
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        if (property == "video-params") {
            updateHdrVideo {
                it.copy(
                    primaries = value["primaries"]?.asString() ?: it.primaries,
                    transfer = value["gamma"]?.asString() ?: it.transfer,
                    light = value["light"]?.asString() ?: it.light,
                    colorMatrix = value["colormatrix"]?.asString() ?: it.colorMatrix,
                    signalPeak = value["sig-peak"]?.asDouble() ?: it.signalPeak,
                    maxCll = value["max-cll"]?.asDouble() ?: it.maxCll,
                    maxFall = value["max-fall"]?.asDouble() ?: it.maxFall,
                )
            }
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                observeProperties()
                refreshTrackList()
                _state.update { it.copy(idle = false) }
            }
            MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                _state.update { it.copy(idle = true, playing = false) }
            }
        }
    }

    /**
     * Pull the current MPV `track-list` (returned as JSON by libmpv) and
     * decompose it into separate audio + subtitle track lists on the
     * playback state. Called on FILE_LOADED so the player UI can present
     * Apple TV-style audio/subtitle pickers as soon as playback starts.
     */
    fun refreshTrackList() {
        if (!initialized) return
        val raw = runCatching { mpv.getPropertyString("track-list") }.getOrNull() ?: return
        if (raw.isBlank()) return
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return

        val audio = mutableListOf<MpvTrack>()
        val subs = mutableListOf<MpvTrack>()
        for (el in parsed) {
            val obj = el as? JsonObject ?: continue
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: continue
            val track = MpvTrack(
                id = id,
                type = type.orEmpty(),
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull,
                lang = (obj["lang"] as? JsonPrimitive)?.contentOrNull,
                codec = (obj["codec"] as? JsonPrimitive)?.contentOrNull,
                selected = (obj["selected"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            when (type) {
                "audio" -> audio += track
                "sub" -> subs += track
            }
        }
        _state.update { it.copy(audioTracks = audio, subtitleTracks = subs) }
    }

    private fun observeProperties() {
        try {
            mpv.observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("idle-active", MPV.mpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("hwdec-current", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("video-codec", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("audio-codec-name", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("video-params", MPV.mpvFormat.MPV_FORMAT_NODE)
            mpv.observeProperty("video-params/primaries", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("video-params/gamma", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("video-params/light", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("video-params/colormatrix", MPV.mpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("video-params/sig-peak", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("video-params/max-cll", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("video-params/max-fall", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("aid", MPV.mpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty("sid", MPV.mpvFormat.MPV_FORMAT_INT64)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to observe some properties", e)
        }
    }

    private fun updateHdrVideo(update: (HdrVideoMetadata) -> HdrVideoMetadata) {
        _state.update { it.copy(hdrVideo = update(it.hdrVideo)) }
    }
}
