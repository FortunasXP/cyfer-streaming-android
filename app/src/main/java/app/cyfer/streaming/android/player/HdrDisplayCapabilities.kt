package app.cyfer.streaming.android.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

enum class HdrDisplayFormat(val label: String, val shortLabel: String) {
    DolbyVision("Dolby Vision", "DV"),
    Hdr10Plus("HDR10+", "HDR10+"),
    Hdr10("HDR10", "HDR10"),
    Hlg("HLG", "HLG"),
}

/**
 * System-level HDR conversion mode (Android 14+). Mirrors AOSP's
 * `HdrConversionMode.HDR_CONVERSION_*` constants but as a Kotlin enum
 * we can read on every API level without reflection issues.
 *  - [Unsupported]  — older device, no HDR conversion API.
 *  - [Passthrough]  — system passes HDR through; what we want.
 *  - [SystemTonemap] — system tone-maps to SDR even when we send HDR.
 *  - [ForceHdr]     — system upgrades SDR to HDR; HDR content passes through.
 */
enum class HdrSystemConversionMode { Unsupported, Passthrough, SystemTonemap, ForceHdr, Unknown }

data class HdrDisplayCapabilities(
    val formats: Set<HdrDisplayFormat> = emptySet(),
    val wideColorGamut: Boolean = false,
    val desiredMaxLuminance: Float? = null,
    val desiredMaxAverageLuminance: Float? = null,
    val desiredMinLuminance: Float? = null,
    /** Android 14+ system HDR conversion mode. Tells us whether the OS
     *  will tone-map our HDR output back to SDR (SystemTonemap = bad). */
    val systemHdrConversion: HdrSystemConversionMode = HdrSystemConversionMode.Unknown,
    /** Android 14+ HDR/SDR luminance ratio derived from display
     *  brightness. 1.0 = no HDR head-room. Higher = more head-room. */
    val hdrSdrRatio: Float? = null,
) {
    val hdrCapable: Boolean get() = formats.isNotEmpty()

    val orderedFormats: List<HdrDisplayFormat>
        get() = HDR_FORMAT_ORDER.filter { it in formats }

    val label: String
        get() = when {
            formats.isEmpty() -> if (wideColorGamut) "SDR · wide color" else "SDR"
            else -> orderedFormats.joinToString(" · ") { it.label }
        }

    val shortLabel: String
        get() = when {
            formats.isEmpty() -> if (wideColorGamut) "WCG" else "SDR"
            else -> orderedFormats.joinToString("/") { it.shortLabel }
        }
}

data class HdrVideoMetadata(
    val primaries: String? = null,
    val transfer: String? = null,
    val light: String? = null,
    val colorMatrix: String? = null,
    /** "limited" (TV, 16–235) or "full" (PC, 0–255). A decoder that
     *  mis-tags this is the most common Android colour bug — washed-out
     *  greys (limited treated as full) or crushed shadows (reverse). */
    val colorLevels: String? = null,
    /** Decoder output pixel format ("p010" = real 10-bit decode,
     *  "nv12" = 8-bit — 10-bit HDR content decoded to nv12 means the
     *  SoC quietly threw away two bits and banding is back). */
    val pixelFormat: String? = null,
    /** Underlying format of hardware (zero-copy) frames — the one that
     *  matters when pixelFormat just says "mediacodec". */
    val hwPixelFormat: String? = null,
    val signalPeak: Double? = null,
    val maxCll: Double? = null,
    val maxFall: Double? = null,
    /** Dolby Vision profile (5, 7, 8) if reported by libmpv. */
    val dolbyVisionProfile: Int? = null,
    /** Dolby Vision compatibility level (1 = HDR10-compat, 4 = HLG-compat). */
    val dolbyVisionLevel: Int? = null,
    /** Active decode path as reported by mpv's `hwdec-current`:
     *  "mediacodec" (zero-copy surface), "mediacodec-copy" (HW decode,
     *  planes copied to memory), or "no" (software). Context for the DV
     *  reshape signals below — the reshape is path-dependent. */
    val hwdecActive: String? = null,
) {
    private val haystack: String
        get() = listOfNotNull(primaries, transfer, light, colorMatrix)
            .joinToString(" ")
            .lowercase()

    val active: Boolean
        get() = haystack.contains("pq") ||
            haystack.contains("2084") ||
            haystack.contains("hlg") ||
            haystack.contains("hdr") ||
            haystack.contains("dolby") ||
            dolbyVisionProfile != null ||
            (signalPeak ?: 0.0) > 1.0 ||
            (maxCll ?: 0.0) > 0.0 ||
            (maxFall ?: 0.0) > 0.0

    /**
     * True when libplacebo will actually apply the DV RPU reshape. Two
     * conditions, both required:
     *  1. The decoded frames carry RPU metadata — mpv flips
     *     `video-params/colormatrix` to "dolbyvision" only then. Our
     *     patched FFmpeg parses RPUs in the mediacodec wrapper too, so
     *     hardware-decoded frames now qualify.
     *  2. libplacebo receives the raw YUV planes. On the zero-copy
     *     surface path ("mediacodec") the GPU driver converts frames to
     *     RGB with a fixed YCbCr matrix at sample time — P5's ICtCp
     *     samples are scrambled before the reshape shader runs, so RPUs
     *     present + zero-copy still renders wrong colours. Copy mode and
     *     software decode upload the true planes; the reshape is
     *     faithful there.
     */
    val dolbyVisionReshapeActive: Boolean
        get() = (colorMatrix ?: "").lowercase().contains("dolbyvision") &&
            hwdecActive != "mediacodec"

    /** RPUs are flowing but the zero-copy surface path blocks a faithful
     *  reshape (driver-side YCbCr→RGB happens before the shader sees the
     *  samples). The player auto-switches DV P5 to mediacodec-copy when
     *  it detects the profile, so for P5 this state is transient. */
    val dolbyVisionRpuBlockedByZeroCopy: Boolean
        get() = (colorMatrix ?: "").lowercase().contains("dolbyvision") &&
            hwdecActive == "mediacodec"

    /**
     * DV profile 5 rendering without the reshape: the P5 base layer is
     * IPTPQc2-encoded, so without the RPU pass the colours are wrong
     * (the classic green/purple tint). The player auto-switches P5 to
     * mediacodec-copy, so if this persists the copy path also failed to
     * surface RPUs — the remedy is an HDR10 or DV P8 source instead.
     */
    val dolbyVisionP5BaseLayer: Boolean
        get() = dolbyVisionProfile == 5 && !dolbyVisionReshapeActive

    val label: String
        get() = when {
            haystack.contains("dolby") || dolbyVisionProfile != null -> "Dolby Vision"
            haystack.contains("hdr10+") || haystack.contains("hdr10plus") -> "HDR10+"
            haystack.contains("hlg") || haystack.contains("arib-std-b67") -> "HLG"
            active -> "HDR10"
            else -> "SDR"
        }

    /**
     * Compact label including the DV profile when known.
     * Examples: "DV P5", "DV P7→HDR10", "DV P8.1", "HDR10+", "HDR10", "SDR".
     * P7 is annotated because our libplacebo build can't decode the
     * dual-layer enhancement layer — we play the HDR10 base layer.
     */
    val detailedLabel: String
        get() {
            val profile = dolbyVisionProfile
            if (profile != null) {
                val tag = when (profile) {
                    5 -> "P5"
                    7 -> "P7→HDR10"
                    // P8 sub-flavour: mpv's `dolby-vision-level` is the
                    // bitstream complexity level (1–13), NOT the
                    // bl_signal_compatibility_id — a 1080p P8.1 file is
                    // level 4 and must not be labelled P8.4. The real
                    // tell is the base layer's transfer: 8.4 = HLG BL.
                    8 -> if ((transfer ?: "").lowercase().let {
                        it.contains("hlg") || it.contains("arib-std-b67")
                    }) "P8.4" else "P8.1"
                    else -> "P$profile"
                }
                return "DV $tag"
            }
            return label
        }
}

object HdrDisplayDetector {
    @Suppress("DEPRECATION")
    fun detect(context: Context): HdrDisplayCapabilities {
        val display = context.displayCompat()
        val hdrFormats = mutableSetOf<HdrDisplayFormat>()
        var maxLum: Float? = null
        var maxAvgLum: Float? = null
        var minLum: Float? = null

        if (display != null) {
            val caps = display.hdrCapabilities
            for (type in caps.supportedHdrTypes) {
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdrFormats += HdrDisplayFormat.DolbyVision
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdrFormats += HdrDisplayFormat.Hdr10
                    Display.HdrCapabilities.HDR_TYPE_HLG -> hdrFormats += HdrDisplayFormat.Hlg
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
                        ) {
                            hdrFormats += HdrDisplayFormat.Hdr10Plus
                        }
                    }
                }
            }
            maxLum = caps.desiredMaxLuminance.takeIf { it.isFinite() && it > 0f }
            maxAvgLum = caps.desiredMaxAverageLuminance.takeIf { it.isFinite() && it > 0f }
            minLum = caps.desiredMinLuminance.takeIf { it.isFinite() && it >= 0f }
        }

        val wideColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (display?.isWideColorGamut == true || context.resources.configuration.isScreenWideColorGamut)

        // Android 14+ system HDR conversion mode: read whether the OS
        // will pass our HDR through, tone-map it to SDR, or upgrade SDR
        // to HDR. If `SystemTonemap` we know our HDR pipeline is being
        // clamped by the OS regardless of what we send. Reflection-safe
        // — fall back to Unknown on older devices.
        val (sysMode, hdrSdrRatio) = readSystemConversion(context, display)

        return HdrDisplayCapabilities(
            formats = hdrFormats,
            wideColorGamut = wideColor,
            desiredMaxLuminance = maxLum,
            desiredMaxAverageLuminance = maxAvgLum,
            desiredMinLuminance = minLum,
            systemHdrConversion = sysMode,
            hdrSdrRatio = hdrSdrRatio,
        )
    }

    private fun readSystemConversion(
        context: Context,
        display: Display?,
    ): Pair<HdrSystemConversionMode, Float?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return HdrSystemConversionMode.Unsupported to null
        }
        val dm = runCatching {
            context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        }.getOrNull() ?: return HdrSystemConversionMode.Unknown to null

        val mode = runCatching {
            val m = dm.javaClass.getMethod("getHdrConversionModeSetting")
            val res = m.invoke(dm) ?: return@runCatching HdrSystemConversionMode.Unknown
            val raw = res.javaClass.getMethod("getConversionMode").invoke(res) as? Int
                ?: return@runCatching HdrSystemConversionMode.Unknown
            // Mirror the AOSP HdrConversionMode constants without an
            // import (introduced in API 34).
            when (raw) {
                0 -> HdrSystemConversionMode.Unsupported
                1 -> HdrSystemConversionMode.Passthrough
                2 -> HdrSystemConversionMode.SystemTonemap
                3 -> HdrSystemConversionMode.ForceHdr
                else -> HdrSystemConversionMode.Unknown
            }
        }.getOrDefault(HdrSystemConversionMode.Unknown)

        val ratio = runCatching {
            val m = Display::class.java.getMethod("getHdrSdrRatio")
            (m.invoke(display) as? Float)?.takeIf { it.isFinite() }
        }.getOrNull()
        return mode to ratio
    }

    /**
     * Live HDR/SDR headroom updates (Android 14+). The ratio is
     * SurfaceFlinger's actual grant — 1.0 means our window is being
     * composited at SDR brightness; >1.0 means HDR pixels really are
     * being displayed brighter than SDR white. It typically sits at 1.0
     * until HDR content hits the screen, which is why the launch-time
     * snapshot in [detect] reads as "no HDR" even on a working pipeline.
     * Returns an unregister lambda, or null when the API/display is
     * unavailable.
     */
    fun listenHdrSdrRatio(context: Context, onRatio: (Float) -> Unit): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val display = context.displayCompat() ?: return null
        return runCatching {
            if (!display.isHdrSdrRatioAvailable) return null
            val listener = java.util.function.Consumer<Display> { d ->
                onRatio(d.hdrSdrRatio)
            }
            display.registerHdrSdrRatioChangedListener(context.mainExecutor, listener)
            onRatio(display.hdrSdrRatio)
            return@runCatching { display.unregisterHdrSdrRatioChangedListener(listener) }
        }.getOrNull()
    }

    fun applyHdrWindowMode(activity: Activity, caps: HdrDisplayCapabilities): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val originalColorMode = activity.window.colorMode
        activity.window.colorMode = if (caps.hdrCapable) {
            ActivityInfo.COLOR_MODE_HDR
        } else if (caps.wideColorGamut) {
            ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        } else {
            ActivityInfo.COLOR_MODE_DEFAULT
        }
        return { activity.window.colorMode = originalColorMode }
    }
}

private val HDR_FORMAT_ORDER = listOf(
    HdrDisplayFormat.DolbyVision,
    HdrDisplayFormat.Hdr10Plus,
    HdrDisplayFormat.Hdr10,
    HdrDisplayFormat.Hlg,
)

@Suppress("DEPRECATION")
private fun Context.displayCompat(): Display? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return display
    }
    findActivity()?.windowManager?.defaultDisplay?.let { return it }
    return null
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
