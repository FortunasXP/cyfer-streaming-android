package app.cyfer.streaming.android.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.delay

private const val SCRUB_DRAG_THRESHOLD = 32f

/**
 * Two-zone gesture surface for the player:
 *  • Left half — single tap toggles controls, double tap rewinds 10s,
 *    vertical drag controls screen brightness.
 *  • Right half — single tap toggles controls, double tap forwards 10s,
 *    vertical drag controls media-stream volume.
 *
 * Mirrors the MX/VLC-style gesture model most Android players ship.
 * Shows a small HUD chip while a drag is in flight.
 */
@Composable
fun PlayerGestureSurface(
    onToggleControls: () -> Unit,
    onDoubleTapSkip: (Int) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
    }
    // Fractional volume accumulator so small per-event drag deltas don't
    // round to zero (single-event delta is typically ~0.005 * maxVolume,
    // which `.toInt()` would silently throw away — that's the "volume
    // gesture not working" symptom).
    var volumeAcc by remember { mutableFloatStateOf(0f) }

    // Live HUD value: 0.0..1.0 — null when the HUD shouldn't be visible.
    var hud by remember { mutableStateOf<HudState?>(null) }

    // Auto-dismiss the HUD shortly after the last change.
    LaunchedEffect(hud?.token) {
        if (hud != null) {
            delay(700)
            hud = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            GestureZone(
                modifier = Modifier.weight(1f),
                onSingleTap = onToggleControls,
                onDoubleTap = { onDoubleTapSkip(-10) },
                onVerticalDrag = { dragFraction ->
                    val window = activity?.window ?: return@GestureZone
                    val params = window.attributes
                    val current = if (params.screenBrightness >= 0f) params.screenBrightness else 0.5f
                    // dragFraction is positive when finger moves DOWN, so
                    // invert: dragging up should increase brightness.
                    val next = (current - dragFraction).coerceIn(0.01f, 1f)
                    params.screenBrightness = next
                    window.attributes = params
                    hud = HudState(
                        kind = HudKind.Brightness,
                        value = next,
                        token = System.nanoTime(),
                    )
                },
            )
            GestureZone(
                modifier = Modifier.weight(1f),
                onSingleTap = onToggleControls,
                onDoubleTap = { onDoubleTapSkip(10) },
                onVerticalDrag = { dragFraction ->
                    val am = audioManager ?: return@GestureZone
                    // 1 full screen swipe (top→bottom) sweeps across the
                    // full volume range. Pointer-input fires many small
                    // events, so accumulate fractional units and flush
                    // when the accumulator crosses an integer boundary.
                    volumeAcc += -dragFraction * maxVolume
                    val deltaUnits = volumeAcc.toInt()
                    if (deltaUnits == 0) return@GestureZone
                    volumeAcc -= deltaUnits
                    val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val next = (current + deltaUnits).coerceIn(0, maxVolume)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                    hud = HudState(
                        kind = HudKind.Volume,
                        value = next.toFloat() / maxVolume,
                        token = System.nanoTime(),
                    )
                },
            )
        }

        AnimatedVisibility(
            visible = hud != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            hud?.let { GestureHud(it) }
        }
    }
}

@Composable
private fun GestureZone(
    modifier: Modifier,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onVerticalDrag: (dragFractionDelta: Float) -> Unit,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { onDoubleTap() },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    if (kotlin.math.abs(dragAmount) < 1f) return@detectVerticalDragGestures
                    onVerticalDrag(dragAmount / heightPx.coerceAtLeast(1f))
                    change.consume()
                }
            },
    )
}

private enum class HudKind { Volume, Brightness }
private data class HudState(val kind: HudKind, val value: Float, val token: Long)

@Composable
private fun GestureHud(state: HudState) {
    val icon: ImageVector = when (state.kind) {
        HudKind.Volume -> if (state.value <= 0.001f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
        HudKind.Brightness -> if (state.value < 0.5f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh
    }
    val pct = (state.value * 100).toInt().coerceIn(0, 100)
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            // 110dp track + filled portion
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(state.value)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
            }
            Text(
                text = "$pct%",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
