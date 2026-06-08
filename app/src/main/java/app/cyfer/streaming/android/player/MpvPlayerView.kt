package app.cyfer.streaming.android.player

import android.graphics.PixelFormat
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
                    if (hdrCaps.hdrCapable || hdrCaps.wideColorGamut) {
                        holder.setFormat(PixelFormat.RGBA_1010102)
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
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
