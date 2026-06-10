package app.cyfer.streaming.android.player

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.util.Log

/**
 * Probes the device's EGL stack for the two things HDR output actually
 * needs from GL, *before* mpv spins up its own context:
 *
 *  1. A window-renderable EGLConfig with a 10-bit (RGB10_A2) or FP16
 *     colour buffer. mpv defaults to an 8-bit config; passing the
 *     10-bit config's EGL_CONFIG_ID via `--egl-config-id` is what makes
 *     the framebuffer wide enough to carry PQ without banding.
 *  2. The `EGL_EXT_gl_colorspace_bt2020_pq` extension. When present,
 *     libplacebo (vo=gpu-next + target-colorspace-hint=yes) asks EGL to
 *     tag the swapchain BT.2020-PQ, SurfaceFlinger composites it as
 *     HDR, and mpv's `hdr-display-detected` flips true. When absent the
 *     GL stack simply cannot signal HDR — the vendor blobs are the
 *     bottleneck, not our pipeline — and the honest move is to say so
 *     in the diagnostic instead of letting the user chase settings.
 */
object EglHdrProbe {

    private const val TAG = "EglHdrProbe"

    // From EGL_EXT_pixel_format_float — not in android.opengl constants.
    private const val EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339
    private const val EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B

    data class Result(
        /** EGL_CONFIG_ID of a window-renderable RGB10_A2 config, if any. */
        val tenBitConfigId: Int? = null,
        /** EGL_CONFIG_ID of a window-renderable FP16 config, if any. */
        val fp16ConfigId: Int? = null,
        /** EGL_EXT_gl_colorspace_bt2020_pq present — HDR10/PQ output possible. */
        val bt2020PqExtension: Boolean = false,
        /** EGL_EXT_gl_colorspace_bt2020_hlg present. */
        val bt2020HlgExtension: Boolean = false,
        /** EGL_EXT_gl_colorspace_display_p3 present — wide-gamut SDR. */
        val displayP3Extension: Boolean = false,
    ) {
        /** Config to hand mpv: prefer 10-bit (half the bandwidth of FP16). */
        val preferredHdrConfigId: Int? get() = tenBitConfigId ?: fp16ConfigId

        /** Both pieces present — the GL stack can genuinely emit HDR. */
        val hdrOutputPossible: Boolean
            get() = preferredHdrConfigId != null && (bt2020PqExtension || bt2020HlgExtension)

        /** Compact one-liner for the diagnostic overlay. */
        val summary: String get() = buildString {
            when {
                tenBitConfigId != null -> append("10bit cfg#$tenBitConfigId")
                fp16ConfigId != null -> append("fp16 cfg#$fp16ConfigId")
                else -> append("8bit only")
            }
            append(" · pq ext ")
            append(if (bt2020PqExtension) "yes" else "no")
            if (bt2020HlgExtension) append(" · hlg ext yes")
        }
    }

    @Volatile private var cached: Result? = null

    /**
     * Run the probe once and cache. Safe to call from any thread; uses
     * the default display and deliberately does NOT eglTerminate — the
     * display stays initialised for mpv's own context (terminating here
     * could tear down state under a concurrently-initialising consumer).
     */
    fun probe(): Result {
        cached?.let { return it }
        val result = runCatching { doProbe() }
            .onFailure { Log.w(TAG, "EGL probe failed: ${it.message}") }
            .getOrDefault(Result())
        cached = result
        Log.i(TAG, "EGL probe: ${result.summary} (hdrOutputPossible=${result.hdrOutputPossible})")
        return result
    }

    private fun doProbe(): Result {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return Result()
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return Result()

        val extensions = EGL14.eglQueryString(dpy, EGL14.EGL_EXTENSIONS).orEmpty()

        val count = IntArray(1)
        EGL14.eglGetConfigs(dpy, null, 0, 0, count, 0)
        val configs = arrayOfNulls<EGLConfig>(count[0].coerceAtLeast(1))
        EGL14.eglGetConfigs(dpy, configs, 0, configs.size, count, 0)

        var tenBit: Int? = null
        var fp16: Int? = null
        for (i in 0 until count[0]) {
            val cfg = configs[i] ?: continue
            fun attr(name: Int): Int {
                val v = IntArray(1)
                return if (EGL14.eglGetConfigAttrib(dpy, cfg, name, v, 0)) v[0] else -1
            }
            // Must be able to back an on-screen window with GLES2/3.
            if (attr(EGL14.EGL_SURFACE_TYPE) and EGL14.EGL_WINDOW_BIT == 0) continue
            val renderable = attr(EGL14.EGL_RENDERABLE_TYPE)
            val gles = renderable and EGL14.EGL_OPENGL_ES2_BIT != 0 ||
                renderable and EGLExt.EGL_OPENGL_ES3_BIT_KHR != 0
            if (!gles) continue

            val r = attr(EGL14.EGL_RED_SIZE)
            val g = attr(EGL14.EGL_GREEN_SIZE)
            val b = attr(EGL14.EGL_BLUE_SIZE)
            val cfgId = attr(EGL14.EGL_CONFIG_ID)
            val componentType = attr(EGL_COLOR_COMPONENT_TYPE_EXT)

            if (componentType == EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT) {
                if (r == 16 && g == 16 && b == 16 && fp16 == null) fp16 = cfgId
            } else if (r == 10 && g == 10 && b == 10 && tenBit == null) {
                tenBit = cfgId
            }
            if (tenBit != null && fp16 != null) break
        }

        return Result(
            tenBitConfigId = tenBit,
            fp16ConfigId = fp16,
            bt2020PqExtension = extensions.contains("EGL_EXT_gl_colorspace_bt2020_pq"),
            bt2020HlgExtension = extensions.contains("EGL_EXT_gl_colorspace_bt2020_hlg"),
            displayP3Extension = extensions.contains("EGL_EXT_gl_colorspace_display_p3"),
        )
    }
}
