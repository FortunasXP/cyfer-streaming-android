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

/**
 * One chapter as reported by libmpv's `chapter-list` property. The `time`
 * field is the chapter's start in seconds; the chapter ends at the next
 * chapter's start (or at duration for the final chapter).
 */
data class MpvChapter(
    val index: Int,
    val title: String?,
    val time: Double,
)

/**
 * Player-side stream health. Updated by the PlayerScreen watchdogs based
 * on libmpv's demuxer-cache-time + first-frame observability. Drives the
 * "Reconnecting…" / "Trying software decoder…" toast overlays.
 */
enum class StreamHealth {
    Normal,                 // Playing smoothly, cache full
    Buffering,              // Cache draining but not yet stalled
    Stalled,                // Cache at 0 for too long — recovery imminent
    Recovering,             // Reload in flight
    HardwareCopyFallback,   // mediacodec failed; trying mediacodec-copy
    DecoderFailed,          // both HW modes failed — no SW fallback by design
}

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
    val idle: Boolean = true,
    /** Chapter table from libmpv. Empty for files without chapter
     *  metadata. Refreshed on FILE_LOADED. */
    val chapters: List<MpvChapter> = emptyList(),
    /** Seconds of demuxer cache available ahead of `position`. Used to
     *  render the buffered range on the scrubber. */
    val demuxerCacheSeconds: Double = 0.0,
    /** True when libmpv's `video-target-params` reports an HDR transfer
     *  (PQ/HLG) on the actual render target — i.e. libplacebo negotiated
     *  an HDR swapchain with EGL/SurfaceFlinger instead of silently
     *  falling back to an SDR one. The most reliable signal that HDR is
     *  actually leaving the renderer rather than getting clamped. */
    val hdrPipelineActive: Boolean = false,
    /** Transfer function of the render target ("pq", "hlg", "bt.1886",
     *  …) from `video-target-params`. Empty until the VO configures. */
    val targetTransfer: String = "",
    /** Effective target peak in nits (`video-target-params/max-luma`). */
    val targetLuminanceNits: Double = 0.0,
    /** Stream health surfaced by the PlayerScreen watchdogs. */
    val streamHealth: StreamHealth = StreamHealth.Normal,
    /** Human-readable summary of the active HDR output decision, e.g.
     *  "DV P8.1 → PQ out (600 nit) · display HDR10/HLG". Empty until
     *  the first plan is computed. */
    val outputPlanDescription: String = "",
    /** EGL probe summary — framebuffer depth + PQ colorspace extension
     *  availability, e.g. "10bit cfg#34 · pq ext yes". Tells us whether
     *  the GL driver can physically deliver HDR to the compositor. */
    val eglSummary: String = "",
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
    /** Last URL we asked mpv to load. Used by reloadCurrent() so the
     *  stall + first-frame watchdogs can recover without the caller
     *  having to thread it through. */
    @Volatile private var currentUrl: String? = null

    fun initialize(context: Context) {
        if (initialized) return
        try {
            val hdrCaps = HdrDisplayDetector.detect(context)
            // Probe the EGL stack before mpv creates its context — tells
            // us whether the GL driver offers a 10-bit framebuffer and
            // the BT.2020-PQ colorspace extension (both required for the
            // panel to actually receive HDR pixels from GL).
            val egl = EglHdrProbe.probe()
            _state.update { it.copy(hdrDisplay = hdrCaps, eglSummary = egl.summary) }
            mpv.create(context)
            applyBaseOptions(hdrCaps, egl)
            mpv.addObserver(this)
            mpv.init()
            initialized = true
            Log.i(TAG, "MPV initialized successfully; display=${hdrCaps.label}; egl=${egl.summary}")
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

    /** Live HDR/SDR headroom push from the Android 14+ display listener.
     *  Only touches the diagnostic state — the output plan doesn't key
     *  off the ratio, so no option churn. */
    fun updateHdrSdrRatio(ratio: Float) {
        _state.update { it.copy(hdrDisplay = it.hdrDisplay.copy(hdrSdrRatio = ratio)) }
    }

    private fun applyBaseOptions(hdrCaps: HdrDisplayCapabilities, egl: EglHdrProbe.Result) {
        // ── Renderer ────────────────────────────────────────────────
        // gpu-next is mpv's libplacebo-based renderer: much better HDR
        // pipeline, perceptual gamut mapping, hybrid tone-mapping, and
        // higher-quality scalers than the legacy gpu vo. Falls back
        // silently to `gpu` if the lib build doesn't support it.
        setOption("vo", "gpu-next")
        setOption("gpu-context", "android")
        setOption("gpu-api", "opengl")
        // mpv defaults to an 8-bit EGLConfig. On an HDR-capable panel,
        // force the 10-bit (or FP16) config found by the probe so the
        // framebuffer can actually carry PQ — without this the HDR
        // signal is quantised to 8 bits before it ever reaches the
        // compositor and the target params never report PQ.
        if (hdrCaps.hdrCapable) {
            egl.preferredHdrConfigId?.let { cfgId ->
                Log.i(TAG, "Using HDR-capable EGLConfig id=$cfgId")
                setOption("egl-config-id", cfgId.toString())
            }
        }
        // Honour any user override applied via setHardwareDecoding before
        // initialize(); falls back to the mediacodec default otherwise.
        setOption("hwdec", currentHwdec)
        // Never silently fall back to software decoding — on phone SoCs
        // software 4K HEVC is unplayable and just cooks the battery. The
        // watchdog cascade tries mediacodec → mediacodec-copy and then
        // reports failure honestly instead.
        setOption("vd-lavc-software-fallback", "no")
        setOption("ao", "audiotrack")
        setOption("input-default-bindings", "yes")
        setOption("keep-open", "yes")
        setOption("save-position-on-quit", "no")
        setOption("force-window", "no")
        // 10-bit-aware dithering — kills banding on HDR-source gradients
        // when we tone-map down to an 8-bit panel.
        setOption("dither-depth", "auto")
        // Pick up any system-level color profile (some OEM ROMs ship one).
        setOption("icc-profile-auto", "yes")

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

    /** User-tunable HDR pipeline config. Defaults match what
     *  [applyHdrOptions] used to set hard-coded. */
    data class HdrPipelineConfig(
        val toneMapping: String = "bt.2390",
        val gamutMappingMode: String = "perceptual",
        val sdrTargetPeakNits: Int = 203,
        /** Force BT.2020/PQ output regardless of what
         *  [HdrDisplayCapabilities.hdrCapable] reports. Override path
         *  for devices whose OS advertises SDR but whose
         *  panel+compositor can in fact display HDR pixels (custom
         *  ROMs, A14+ AOSP variants). */
        val forceHdrOutput: Boolean = false,
        /** Target peak nits when forceHdrOutput is on. */
        val forcedHdrPeakNits: Int = 600,
    )

    @Volatile private var currentHdrConfig: HdrPipelineConfig = HdrPipelineConfig()

    /** Push a fresh HDR config in. Reapplies all libplacebo HDR options. */
    fun applyHdrConfig(config: HdrPipelineConfig) {
        currentHdrConfig = config
        if (initialized) {
            applyHdrOptions(_state.value.hdrDisplay)
        }
    }

    /** Last applied plan — lets us skip redundant option churn when
     *  video-params fire repeatedly with the same effective values. */
    @Volatile private var lastOutputPlan: HdrOutputPlan? = null

    private fun applyHdrOptions(hdrCaps: HdrDisplayCapabilities) {
        val cfg = currentHdrConfig
        // ── HDR pipeline ────────────────────────────────────────────
        // hdr-peak-percentile=99.995 ignores stray specular hot-pixels
        // so an average dark scene doesn't get its tone-curve blown out
        // by a single bright sub-pixel.
        setOption("hdr-compute-peak", "auto")
        setOption("hdr-peak-percentile", "99.995")
        setOption("tone-mapping", cfg.toneMapping)
        setOption("gamut-mapping-mode", cfg.gamutMappingMode)
        // Recover highlight detail crushed by the tone curve — mpv's
        // high-quality profile value. Mostly benefits HDR→SDR output;
        // a no-op when the target is genuine PQ/HLG.
        setOption("hdr-contrast-recovery", "0.30")
        // NOTE deliberately absent options (verified against the bundled
        // libmpv, mpv v0.41-master):
        //  - tone-mapping-mode: removed upstream in mpv 0.37; libplacebo
        //    picks the hybrid behaviour automatically now.
        //  - vd-lavc-o=dovi_strip: never existed in FFmpeg — the old
        //    "strip DV → HDR10" mode was a silent no-op. On the
        //    mediacodec path the base layer is all we get anyway (the
        //    HW wrapper decoder doesn't parse RPUs), so P8 content
        //    already renders as plain HDR10.

        applyOutputPlan(hdrCaps)
    }

    /**
     * Content-format × display-capability decision. Re-runs whenever
     * the source metadata, display caps, or user config change, so a
     * DV file landing on an HDR10 panel and an HLG broadcast on an
     * HLG panel each get the right output transfer. Deduped against
     * the last applied plan because video-params fires several times
     * during a load.
     */
    private fun applyOutputPlan(hdrCaps: HdrDisplayCapabilities) {
        val plan = planHdrOutput(_state.value.hdrVideo, hdrCaps, currentHdrConfig)
        if (plan == lastOutputPlan) return
        lastOutputPlan = plan
        Log.i(TAG, "HDR output plan: ${plan.description}")

        setOption("target-colorspace-hint", if (plan.colorspaceHint) "yes" else "no")
        if (plan.colorspaceHint) {
            setOption("target-colorspace-hint-mode", "source-dynamic")
        }
        setOption("target-prim", plan.targetPrim)
        setOption("target-trc", plan.targetTrc)
        setOption("target-peak", plan.targetPeak)
        setOption("target-contrast", plan.targetContrast)
        _state.update { it.copy(outputPlanDescription = plan.description) }
    }

    /** Recompute the output plan against the current display caps —
     *  called when fresh source metadata lands (file load, video-params). */
    private fun reapplyOutputPlan() {
        if (!initialized) return
        applyOutputPlan(_state.value.hdrDisplay)
    }

    private fun setOption(name: String, value: String) {
        runCatching { mpv.setOptionString(name, value) }
            .onFailure { Log.w(TAG, "MPV option rejected: $name=$value (${it.message})") }
    }

    fun attachSurface(surface: Surface) {
        if (!initialized) return
        runCatching {
            mpv.attachSurface(surface)
            // Rebuild the video output on the new surface. When the phone
            // locks, surfaceDestroyed tears the EGL context down; a bare
            // re-attach on unlock leaves the VO null and the video stays
            // black. Re-asserting vo=gpu-next forces libplacebo to
            // recreate its swapchain against the fresh surface. This is
            // the canonical mpv-android resume fix.
            mpv.setOptionString("vo", "gpu-next")
            mpv.setOptionString("force-window", "yes")
        }.onFailure { Log.w(TAG, "Failed to attach MPV surface", it) }
    }

    fun detachSurface() {
        if (!initialized) return
        runCatching {
            // Drop the video output BEFORE releasing the surface so mpv
            // tears its swapchain down cleanly instead of rendering into
            // a dead window (which is what leaves it wedged on resume).
            mpv.setOptionString("vo", "null")
            mpv.detachSurface()
        }.onFailure { Log.w(TAG, "Failed to detach MPV surface", it) }
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
        currentUrl = url
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
                streamHealth = StreamHealth.Normal,
                chapters = emptyList(),
                demuxerCacheSeconds = 0.0,
                outputPlanDescription = "",
                hdrPipelineActive = false,
                targetTransfer = "",
                targetLuminanceNits = 0.0,
            )
        }
        // Drop the previous file's output plan so the next file's
        // metadata recomputes from scratch (a DV plan must not leak
        // into an SDR file that follows it).
        lastOutputPlan = null
        reapplyOutputPlan()
        mpv.command("loadfile", url)
    }

    /**
     * Re-issue loadfile for the currently-playing URL. Used by the stall
     * watchdog to recover from a dead source without losing the user's
     * position. Caller must seek back to the saved position once playback
     * resumes (position fires past 0 again).
     */
    fun reloadCurrent() {
        val url = currentUrl ?: return
        if (!initialized) return
        Log.i(TAG, "Reloading current URL for stall recovery")
        _state.update { it.copy(streamHealth = StreamHealth.Recovering) }
        mpv.command("loadfile", url)
    }

    /**
     * Try the copy-mode HW decoder before giving up on hardware entirely.
     * `mediacodec-copy` reads frames from MediaCodec into system memory
     * instead of relying on the zero-copy surface path. Slightly higher
     * CPU but handles DV-marked HEVC + some quirky AV1 streams that
     * choke the zero-copy `mediacodec` mode.
     * Used by the first-frame watchdog as the intermediate fallback —
     * we go HW → mediacodec-copy → SW, with SW reserved for true
     * last-resort because a 2018 phone software-decoding 4K HEVC is
     * effectively unplayable.
     */
    fun switchToMediacodecCopy() {
        if (!initialized) return
        if (currentHwdec == "mediacodec-copy") return
        Log.i(TAG, "Switching to mediacodec-copy as intermediate fallback")
        currentHwdec = "mediacodec-copy"
        setOption("hwdec", "mediacodec-copy")
        _state.update { it.copy(streamHealth = StreamHealth.HardwareCopyFallback) }
        currentUrl?.let { mpv.command("loadfile", it) }
    }

    /**
     * Both hardware decode modes failed to produce a frame. There is no
     * software fallback by design — SW 4K HEVC on a phone SoC is
     * unplayable and just cooks the battery — so surface the failure
     * honestly and let the user pick a different source.
     */
    fun markDecoderFailed() {
        Log.w(TAG, "Hardware decoder failed in both mediacodec and mediacodec-copy modes")
        _state.update { it.copy(streamHealth = StreamHealth.DecoderFailed) }
    }

    /** Clear a transient stream-health badge once we're back to normal. */
    fun clearStreamHealth() {
        _state.update { it.copy(streamHealth = StreamHealth.Normal) }
    }

    fun getCurrentUrl(): String? = currentUrl

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

    /**
     * Fast keyframe-accurate seek used by the live-scrubber. The third
     * arg `exact` would be more precise but takes longer to render the
     * first frame — for scrub previews we want the closest keyframe so
     * the surface updates instantly as the finger moves.
     */
    fun scrubTo(positionSec: Double) {
        if (!initialized) return
        runCatching { mpv.command("seek", positionSec.toString(), "absolute+keyframes") }
    }

    fun seekRelative(seconds: Int) {
        if (!initialized) return
        mpv.command("seek", seconds.toString(), "relative")
    }

    /**
     * Jump to a chapter by absolute time. Wraps the chapter-list start
     * time as a regular absolute seek so it works the same on files with
     * or without proper chapter metadata.
     */
    fun seekToChapter(chapter: MpvChapter) {
        if (!initialized) return
        mpv.command("seek", chapter.time.toString(), "absolute")
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
        // No-value change = property became unavailable. For the target
        // params that means the VO tore its swapchain down (surface
        // destroyed / vo=null during lock) — drop the HDR-active flag so
        // the UI doesn't keep claiming HDR output with no output at all.
        if (property == "video-target-params") {
            _state.update {
                it.copy(hdrPipelineActive = false, targetTransfer = "", targetLuminanceNits = 0.0)
            }
        }
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
            "demuxer-cache-time" -> _state.update { it.copy(demuxerCacheSeconds = value) }
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
        when (property) {
            "video-params" -> updateHdrVideo {
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
            "video-target-params" -> {
                // mpv historically calls the transfer "gamma"; newer
                // builds also expose "transfer" — read either.
                val transfer = (value["gamma"]?.asString()
                    ?: value["transfer"]?.asString()).orEmpty().lowercase()
                val peakNits = value["max-luma"]?.asDouble() ?: 0.0
                val hdrOut = transfer == "pq" || transfer == "hlg"
                _state.update {
                    it.copy(
                        hdrPipelineActive = hdrOut,
                        targetTransfer = transfer,
                        targetLuminanceNits = peakNits,
                    )
                }
            }
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                observeProperties()
                refreshTrackList()
                refreshChapterList()
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
        var dvProfile: Int? = null
        var dvLevel: Int? = null
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
                "video" -> {
                    // libmpv exposes Dolby Vision metadata on the active
                    // video track via `dolby-vision-profile` /
                    // `dolby-vision-level` (added in mpv 0.36+ via the
                    // libplacebo+ffmpeg integration). Either field can
                    // be missing for non-DV streams.
                    val isSelected = (obj["selected"] as? JsonPrimitive)?.booleanOrNull ?: false
                    if (isSelected) {
                        dvProfile = (obj["dolby-vision-profile"] as? JsonPrimitive)?.intOrNull
                            ?: (obj["dv-profile"] as? JsonPrimitive)?.intOrNull
                        dvLevel = (obj["dolby-vision-level"] as? JsonPrimitive)?.intOrNull
                            ?: (obj["dv-level"] as? JsonPrimitive)?.intOrNull
                    }
                }
            }
        }
        _state.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subs,
                hdrVideo = it.hdrVideo.copy(
                    dolbyVisionProfile = dvProfile ?: it.hdrVideo.dolbyVisionProfile,
                    dolbyVisionLevel = dvLevel ?: it.hdrVideo.dolbyVisionLevel,
                ),
            )
        }
        if (dvProfile != null) {
            Log.i(TAG, "Detected Dolby Vision profile=$dvProfile level=${dvLevel ?: "?"}")
        }
        // Fresh DV metadata can change the output plan ("DV P8.1 → PQ"
        // vs the generic HDR label) — recompute now that we know.
        reapplyOutputPlan()
    }

    /**
     * Pull the current MPV `chapter-list` (returned as JSON by libmpv)
     * and decompose into [MpvChapter]s. Empty when the file has no
     * chapter metadata. Called on FILE_LOADED.
     */
    fun refreshChapterList() {
        if (!initialized) return
        val raw = runCatching { mpv.getPropertyString("chapter-list") }.getOrNull() ?: return
        if (raw.isBlank()) {
            _state.update { it.copy(chapters = emptyList()) }
            return
        }
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return

        val chapters = mutableListOf<MpvChapter>()
        for ((idx, el) in parsed.withIndex()) {
            val obj = el as? JsonObject ?: continue
            val time = (obj["time"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            chapters += MpvChapter(index = idx, title = title, time = time)
        }
        _state.update { it.copy(chapters = chapters) }
        Log.i(TAG, "Loaded ${chapters.size} chapters")
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
            // Demuxer cache horizon — drives the buffered-range fill on
            // the scrubber. Updates roughly every second while data is
            // arriving; sits flat when the cache is full.
            mpv.observeProperty("demuxer-cache-time", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            // HDR pipeline verification: video-target-params reports the
            // colour params of the ACTUAL render target libplacebo
            // negotiated with EGL/SurfaceFlinger — transfer pq/hlg means
            // HDR pixels are genuinely leaving the renderer; max-luma is
            // the effective output peak in nits. (The previously observed
            // hdr-display-detected / target-luminance properties do not
            // exist in any mpv — verified against the bundled binary.)
            mpv.observeProperty("video-target-params", MPV.mpvFormat.MPV_FORMAT_NODE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to observe some properties", e)
        }
    }

    private fun updateHdrVideo(update: (HdrVideoMetadata) -> HdrVideoMetadata) {
        _state.update { it.copy(hdrVideo = update(it.hdrVideo)) }
        // Source colour metadata feeds the output plan (HLG vs PQ vs SDR
        // source detection) — recompute. Deduped inside, so the repeated
        // video-params/* updates during a load cost almost nothing.
        reapplyOutputPlan()
    }
}
