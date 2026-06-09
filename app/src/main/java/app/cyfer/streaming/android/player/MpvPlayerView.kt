package app.cyfer.streaming.android.player

import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose wrapper that hosts a SurfaceView for libmpv rendering.
 * Must be placed inside a Box (not a Material Surface) to avoid
 * offscreen-rendering issues with SurfaceView.
 */
@Composable
fun MpvPlayerView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize MPV on first composition
    DisposableEffect(Unit) {
        MpvPlayer.initialize(context)
        MpvPlayer.refreshHdrCapabilities(context)
        onDispose {
            // Don't destroy here — let the caller manage lifecycle
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val hdrCaps = HdrDisplayDetector.detect(ctx)
                SurfaceView(ctx).apply {
                    // Pick the most aggressive HDR-friendly pixel format
                    // that the OS will accept. On Android 13+ we also
                    // tag the surface's color space — without that hint
                    // SurfaceFlinger usually treats 10/16-bit buffers as
                    // sRGB and silently tone-maps them to SDR.
                    configureHdrSurfaceFormat(this, hdrCaps)

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Hint the underlying ANativeWindow's color
                            // space on Android 13+. mpv-android wraps
                            // this via gpu-context but we belt-and-brace
                            // it: if the platform exposes the API we set
                            // it here on the Java surface too.
                            tagSurfaceColorSpace(holder.surface, hdrCaps)
                            MpvPlayer.attachSurface(holder.surface)
                            MpvPlayer.setForceWindow(true)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            MpvPlayer.setSurfaceSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            MpvPlayer.setForceWindow(false)
                            MpvPlayer.detachSurface()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private const val TAG = "MpvPlayerView"

/**
 * Choose the HDR pixel format for the SurfaceView's underlying buffer.
 * Order of preference:
 *  1. RGBA_FP16 on Android 13+ (16-bit half-float — the most accurate
 *     HDR container; some HWComposers honor only this).
 *  2. RGBA_1010102 (10/10/10/2 — supported since Android 8 but only
 *     reliably HDR-capable on Android 11+).
 *  3. Default RGBA8888 (8-bit SDR fallback).
 */
private fun configureHdrSurfaceFormat(view: SurfaceView, caps: HdrDisplayCapabilities) {
    val wantHdr = caps.hdrCapable || caps.wideColorGamut
    if (!wantHdr) return
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PixelFormat.RGBA_F16
    } else {
        PixelFormat.RGBA_1010102
    }
    runCatching { view.holder.setFormat(format) }
        .onFailure { Log.w(TAG, "setFormat(${format}) rejected: ${it.message}") }
}

/**
 * On Android 13+ tag the Surface's color space so SurfaceFlinger knows
 * we're sending BT.2020 PQ HDR pixels rather than sRGB. Without this,
 * compositors typically assume sRGB and silently tone-map our output.
 * Reflection-safe — falls back gracefully on devices missing the API.
 */
private fun tagSurfaceColorSpace(surface: android.view.Surface, caps: HdrDisplayCapabilities) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val target = when {
        caps.hdrCapable -> ColorSpace.Named.BT2020_PQ
        caps.wideColorGamut -> ColorSpace.Named.DISPLAY_P3
        else -> return
    }
    runCatching {
        // Surface gained setColorSpace(ColorSpace) in API 33 (Android 13)
        // but the public API hasn't been finalised so it's still hidden
        // on some OEM ROMs. Use reflection so we don't crash where the
        // method is missing.
        val m = surface.javaClass.getMethod("setColorSpace", ColorSpace::class.java)
        m.invoke(surface, ColorSpace.get(target))
        Log.i(TAG, "Tagged surface color space ${target.name}")
    }.onFailure {
        Log.d(TAG, "Surface.setColorSpace unavailable: ${it.javaClass.simpleName}")
    }
}
