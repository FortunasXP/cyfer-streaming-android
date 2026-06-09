package app.cyfer.streaming.android.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display

enum class HdrDisplayFormat(val label: String, val shortLabel: String) {
    DolbyVision("Dolby Vision", "DV"),
    Hdr10Plus("HDR10+", "HDR10+"),
    Hdr10("HDR10", "HDR10"),
    Hlg("HLG", "HLG"),
}

data class HdrDisplayCapabilities(
    val formats: Set<HdrDisplayFormat> = emptySet(),
    val wideColorGamut: Boolean = false,
    val desiredMaxLuminance: Float? = null,
    val desiredMaxAverageLuminance: Float? = null,
    val desiredMinLuminance: Float? = null,
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
    val signalPeak: Double? = null,
    val maxCll: Double? = null,
    val maxFall: Double? = null,
    /** Dolby Vision profile (5, 7, 8) if reported by libmpv. */
    val dolbyVisionProfile: Int? = null,
    /** Dolby Vision compatibility level (1 = HDR10-compat, 4 = HLG-compat). */
    val dolbyVisionLevel: Int? = null,
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
                val level = dolbyVisionLevel
                val tag = when (profile) {
                    5 -> "P5"
                    7 -> "P7→HDR10"
                    8 -> if (level == 4) "P8.4" else "P8.1"
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

        return HdrDisplayCapabilities(
            formats = hdrFormats,
            wideColorGamut = wideColor,
            desiredMaxLuminance = maxLum,
            desiredMaxAverageLuminance = maxAvgLum,
            desiredMinLuminance = minLum,
        )
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
