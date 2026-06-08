package app.cyfer.streaming.android.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.settings.mpvOption
import app.cyfer.streaming.android.data.torrent.TechTag
import app.cyfer.streaming.android.player.HdrDisplayCapabilities
import app.cyfer.streaming.android.player.HdrDisplayDetector
import app.cyfer.streaming.android.player.HdrVideoMetadata
import app.cyfer.streaming.android.player.MpvPlaybackState
import app.cyfer.streaming.android.player.MpvPlayer
import app.cyfer.streaming.android.player.MpvPlayerView
import app.cyfer.streaming.android.ui.common.TechLogoBadge
import app.cyfer.streaming.android.player.MpvTrack
import app.cyfer.streaming.android.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Cyfer player surface — Apple TV-inspired:
 *  - Loading splash uses the title backdrop with a slim arc spinner
 *  - Minimal glass control row, no Netflix-style skip burst
 *  - Track pickers (audio / subtitle) accessible from the control row
 *  - Activity flips to landscape on entry, back to portrait on exit
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String?,
    backdropUrl: String?,
    posterUrl: String? = null,
    mediaType: String? = null,
    tmdbId: Int? = null,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    initialPosition: Double = 0.0,
    onBack: () -> Unit,
    onAdvanceToNext: (app.cyfer.streaming.android.ui.sources.SourcePickerRequest) -> Unit = {},
) {
    val playbackState by MpvPlayer.state.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    var pickerOpen by remember { mutableStateOf<PickerKind?>(null) }
    var onlineSubsOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var playbackSpeed by remember(url) { mutableFloatStateOf(1.0f) }
    var aspectMode by remember(url) { mutableStateOf("Default") }
    var subSize by remember(url) { mutableStateOf("Medium") }
    var subPosition by remember(url) { mutableStateOf("Bottom") }
    var subBackdrop by remember(url) { mutableStateOf(false) }

    // Keep the splash on screen until MPV has actually drawn its first
    // frame. `position > 0` is the most reliable signal — it only ticks
    // forward once frames are being decoded + presented, so it covers
    // every gap (parsing the container, finding peers, hwdec setup, …).
    var hasPlayedFirstFrame by remember(url) { mutableStateOf(false) }
    LaunchedEffect(playbackState.position) {
        if (playbackState.position > 0.05) hasPlayedFirstFrame = true
    }
    val isLoading = !hasPlayedFirstFrame

    // Seek to a saved resume position once the file has actually loaded
    // — we need MPV to know the duration before seeking is meaningful.
    var resumed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(playbackState.duration, initialPosition) {
        if (!resumed && initialPosition > 0.0 && playbackState.duration > 0.0) {
            MpvPlayer.seekTo(initialPosition)
            resumed = true
        }
    }

    // Library hookup for progress save.
    val libCtx = LocalContext.current
    val libraryRepo = remember { app.cyfer.streaming.android.data.library.LibraryRepository.get(libCtx) }
    val libScope = rememberCoroutineScope()
    fun saveProgressSnapshot() {
        if (tmdbId == null || mediaType.isNullOrBlank() || title.isNullOrBlank()) return
        val pos = playbackState.position
        val dur = playbackState.duration
        if (pos <= 0 || dur <= 0) return
        libScope.launch {
            libraryRepo.saveProgress(
                app.cyfer.streaming.android.data.library.ProgressEntry(
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    title = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    season = season,
                    episode = episode,
                    seriesTmdbId = if (mediaType == "tv") tmdbId else null,
                    seriesTitle = if (mediaType == "tv") title else null,
                    position = pos,
                    duration = dur,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // Heartbeat: persist progress every 30s while playing.
    LaunchedEffect(playbackState.idle) {
        while (!playbackState.idle) {
            delay(30_000)
            saveProgressSnapshot()
        }
    }
    DisposableEffect(Unit) {
        onDispose { saveProgressSnapshot() }
    }

    // ── Trakt scrobble — fires start / pause / stop based on MPV state
    //    transitions. The repository swallows errors, so a missing
    //    Trakt session or a 5xx never disrupts playback.
    if (!imdbId.isNullOrBlank()) {
        val traktRepo = remember { app.cyfer.streaming.android.data.trakt.TraktRepository.get(libCtx) }

        fun currentPct(): Float {
            val dur = playbackState.duration
            return if (dur > 0) ((playbackState.position / dur) * 100.0).toFloat().coerceIn(0f, 100f) else 0f
        }
        // Start scrobble once we have a real position (first frame played).
        var startedScrobble by remember(url) { mutableStateOf(false) }
        LaunchedEffect(playbackState.position) {
            if (startedScrobble) return@LaunchedEffect
            if (playbackState.position > 0.05 && playbackState.duration > 0) {
                startedScrobble = true
                traktRepo.scrobble(
                    app.cyfer.streaming.android.data.trakt.TraktRepository.ScrobbleAction.Start,
                    imdbId, currentPct(), season, episode,
                )
            }
        }
        // Pause / resume transitions → scrobble pause / start.
        LaunchedEffect(playbackState.paused) {
            if (!startedScrobble) return@LaunchedEffect
            traktRepo.scrobble(
                if (playbackState.paused)
                    app.cyfer.streaming.android.data.trakt.TraktRepository.ScrobbleAction.Pause
                else
                    app.cyfer.streaming.android.data.trakt.TraktRepository.ScrobbleAction.Start,
                imdbId, currentPct(), season, episode,
            )
        }
        // Stop scrobble on dispose — Trakt marks watched at ≥80%.
        DisposableEffect(Unit) {
            onDispose {
                if (startedScrobble) {
                    libScope.launch {
                        traktRepo.scrobble(
                            app.cyfer.streaming.android.data.trakt.TraktRepository.ScrobbleAction.Stop,
                            imdbId, currentPct(), season, episode,
                        )
                    }
                }
            }
        }
    }

    // ── Orientation: force landscape while the player is on screen ──
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val original = activity?.requestedOrientation
        val hdrCaps = MpvPlayer.refreshHdrCapabilities(context)
        val restoreHdrMode = activity?.let { HdrDisplayDetector.applyHdrWindowMode(it, hdrCaps) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            restoreHdrMode?.invoke()
            if (original != null) activity.requestedOrientation = original
            else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // ── Immersive system bars — hide status + nav bars when the chrome
    //    fades out, so the bright status icons don't keep glaring over a
    //    dark scene. Swipe-from-edge brings them back transiently. ──
    DisposableEffect(controlsVisible, isLoading) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val systemBars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (controlsVisible || isLoading) controller.show(systemBars) else controller.hide(systemBars)
        onDispose {
            // Restore both bars on exit so the rest of the app gets normal chrome back.
            controller.show(systemBars)
        }
    }

    // ── Picture-in-picture awareness — strip the chrome while in PiP ──
    var inPip by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val listener = androidx.core.util.Consumer<android.app.PictureInPictureUiState> { _ ->
            inPip = activity?.isInPictureInPictureMode == true
        }
        // Polling fallback for the legacy API path — the Consumer<> listener
        // requires API 31. On older devices we re-read isInPictureInPictureMode
        // when the user interacts via the gesture surface.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity is androidx.activity.ComponentActivity) {
            activity.addOnPictureInPictureModeChangedListener { info ->
                inPip = info.isInPictureInPictureMode
            }
        }
        onDispose { /* no-op; the listener is bound to the Activity lifecycle */ }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val activity = context.findActivity() ?: return
        runCatching {
            activity.enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible && !isLoading) {
            delay(4500)
            controlsVisible = false
        }
    }
    // Honour the user's hwdec preference. Recomputed if they flip the
    // setting while the player is up — MPV will swap decoders.
    val settingsRepo = remember { app.cyfer.streaming.android.data.settings.SettingsRepository.get(libCtx) }
    val playerSettings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = app.cyfer.streaming.android.data.settings.AppSettings())
    LaunchedEffect(playerSettings.hardwareDecoding) {
        MpvPlayer.setHardwareDecoding(playerSettings.hardwareDecoding.mpvOption())
    }

    LaunchedEffect(url) { MpvPlayer.loadUrl(url, title) }

    // ── Up Next (autoplay + prefetch) ─────────────────────────────
    // Only fires for serialised content (TV/anime) where we have the
    // tmdbId + season + episode triple to walk forward. For movies and
    // ad-hoc playback it's a no-op.
    val isEpisodic = (mediaType == "tv" || mediaType == "anime") && tmdbId != null && season != null && episode != null
    var nextEpisode by remember(tmdbId, season, episode) {
        mutableStateOf<app.cyfer.streaming.android.data.player.NextEpisode?>(null)
    }
    var prefetched by remember(tmdbId, season, episode) { mutableStateOf(false) }
    val playProgress = if (playerSettings.autoplayNextEpisode || playerSettings.prefetchNextEpisode) {
        val dur = playbackState.duration
        if (dur > 0) (playbackState.position / dur).coerceIn(0.0, 1.0) else 0.0
    } else 0.0

    // Discover the next episode just once per current episode — cached.
    LaunchedEffect(isEpisodic, tmdbId, season, episode, playerSettings.autoplayNextEpisode, playerSettings.prefetchNextEpisode) {
        if (!isEpisodic) return@LaunchedEffect
        if (!playerSettings.autoplayNextEpisode && !playerSettings.prefetchNextEpisode) return@LaunchedEffect
        nextEpisode = runCatching {
            app.cyfer.streaming.android.data.player.NextEpisodeLookup.findNext(tmdbId!!, season!!, episode!!)
        }.getOrNull()
    }

    // 50% mark → prefetch next-episode sources into the picker cache.
    LaunchedEffect(playProgress >= 0.5, nextEpisode, playerSettings.prefetchNextEpisode) {
        if (prefetched) return@LaunchedEffect
        if (playProgress < 0.5) return@LaunchedEffect
        val n = nextEpisode ?: return@LaunchedEffect
        if (!playerSettings.prefetchNextEpisode) return@LaunchedEffect
        prefetched = true
        runCatching {
            app.cyfer.streaming.android.ui.sources.prefetchSourceSearch(
                context = libCtx,
                request = nextEpisodeRequest(title, tmdbId!!, mediaType!!, n, backdropUrl, posterUrl),
            )
        }
    }

    // 95% mark + autoplayNextEpisode → show the Up Next card.
    val showUpNext = playerSettings.autoplayNextEpisode && nextEpisode != null && playProgress >= 0.95 && !inPip
    BackHandler {
        MpvPlayer.stop()
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MpvPlayerView(modifier = Modifier.fillMaxSize())

        // Soft vignette so bright frames don't bleed to the OLED edges.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                        radius = 1400f,
                    ),
                ),
        )

        // Gesture surface — taps toggle chrome, double-tap zones skip ±10s,
        // vertical swipes drive brightness (left) and volume (right).
        if (!inPip) {
            PlayerGestureSurface(
                onToggleControls = { controlsVisible = !controlsVisible },
                onDoubleTapSkip = { delta -> MpvPlayer.seekRelative(delta) },
            )
        }

        // Loading splash — backdrop + slim ring + caption
        AnimatedVisibility(
            visible = isLoading && !inPip,
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(durationMillis = 300)),
        ) {
            LoadingSplash(backdropUrl = backdropUrl, title = title)
        }

        // Apple TV-style controls overlay (hidden in PiP)
        AnimatedVisibility(visible = controlsVisible && !isLoading && !inPip, enter = fadeIn(), exit = fadeOut()) {
            ControlsOverlay(
                title = title,
                state = playbackState,
                isSeeking = isSeeking,
                seekPosition = seekPosition,
                onBack = {
                    MpvPlayer.stop()
                    onBack()
                },
                onTogglePause = { MpvPlayer.togglePause() },
                onSeekRel = { MpvPlayer.seekRelative(it) },
                onSeekChange = { v -> isSeeking = true; seekPosition = v },
                onSeekFinished = {
                    MpvPlayer.seekTo(seekPosition.toDouble())
                    isSeeking = false
                },
                onOpenPicker = { pickerOpen = it },
                onEnterPip = { enterPip() },
                onOpenOnlineSubs = { onlineSubsOpen = true },
                onOpenOptions = { optionsOpen = true },
            )
        }

        if (optionsOpen) {
            PlaybackOptionsSheet(
                speed = playbackSpeed,
                aspect = aspectMode,
                subSize = subSize,
                subPosition = subPosition,
                subBackdrop = subBackdrop,
                onSpeed = { v ->
                    playbackSpeed = v
                    MpvPlayer.setPlaybackSpeed(v.toDouble())
                },
                onAspect = { v ->
                    aspectMode = v
                    MpvPlayer.setAspectRatio(
                        when (v) {
                            "16:9" -> "16:9"
                            "4:3" -> "4:3"
                            "21:9" -> "21:9"
                            else -> ""
                        },
                    )
                },
                onSubSize = { v ->
                    subSize = v
                    MpvPlayer.setSubtitleScale(
                        when (v) { "Small" -> 0.75; "Large" -> 1.4; "Huge" -> 1.8; else -> 1.0 },
                    )
                },
                onSubPosition = { v ->
                    subPosition = v
                    MpvPlayer.setSubtitlePosition(if (v == "Top") 5 else 100)
                },
                onSubBackdrop = { v ->
                    subBackdrop = v
                    MpvPlayer.setSubtitleBackdrop(v)
                },
                onDismiss = { optionsOpen = false },
            )
        }

        // ── Online subtitles sheet — addon-driven ─────────────────────
        if (onlineSubsOpen) {
            OnlineSubsSheet(
                imdbId = imdbId,
                mediaType = mediaType ?: "movie",
                season = season,
                episode = episode,
                onDismiss = { onlineSubsOpen = false },
                onPick = { sub ->
                    MpvPlayer.loadExternalSubtitle(sub.url, sub.label, sub.lang)
                    onlineSubsOpen = false
                },
            )
        }

        // ── Up Next card — bottom-right, 10s countdown ───────────────
        AnimatedVisibility(
            visible = showUpNext,
            enter = fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 88.dp)
                .navigationBarsPadding(),
        ) {
            nextEpisode?.let { ne ->
                UpNextCard(
                    episode = ne,
                    onCancel = {
                        // Nudge the position back below 95 % so we don't
                        // keep flashing it. The user can re-trigger by
                        // letting the video play to the very end.
                        MpvPlayer.seekRelative(-30)
                    },
                    onPlayNow = {
                        onAdvanceToNext(
                            nextEpisodeRequest(title, tmdbId!!, mediaType!!, ne, backdropUrl, posterUrl),
                        )
                    },
                )
            }
        }

        pickerOpen?.let { kind ->
            TrackPickerSheet(
                kind = kind,
                state = playbackState,
                onDismiss = { pickerOpen = null },
                onPick = { id ->
                    when (kind) {
                        PickerKind.Audio -> MpvPlayer.setAudioTrack(id)
                        PickerKind.Subtitle -> MpvPlayer.setSubtitleTrack(id)
                    }
                    pickerOpen = null
                },
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════════

private enum class PickerKind { Audio, Subtitle }

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}


@Composable
private fun LoadingSplash(backdropUrl: String?, title: String?) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.65f),
                            ),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingRing(sizeDp = 44)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "PREPARING STREAM",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            if (!title.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LoadingRing(sizeDp: Int) {
    val infinite = rememberInfiniteTransition(label = "loading-ring")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    Canvas(modifier = Modifier.size(sizeDp.dp).rotate(angle)) {
        val stroke = 3.dp.toPx()
        val pad = stroke / 2
        drawArc(
            color = Color.White.copy(alpha = 0.16f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsOverlay(
    title: String?,
    state: MpvPlaybackState,
    isSeeking: Boolean,
    seekPosition: Float,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onSeekRel: (Int) -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onOpenPicker: (PickerKind) -> Unit,
    onEnterPip: () -> Unit,
    onOpenOnlineSubs: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Slim scrims — Apple TV uses minimal darken, just enough for legibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                ),
        )

        // Top: back + title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = onBack, size = 38) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title ?: "Playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.hdrVideo.active) {
                    Spacer(modifier = Modifier.width(8.dp))
                    HdrTitleBadge(state.hdrVideo)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            GlassIconButton(onClick = onEnterPip, size = 38) {
                Icon(Icons.Filled.PictureInPicture, contentDescription = "Picture in picture", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // Center: minimal play/pause + skips. No big burst overlay.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            GlassIconButton(onClick = { onSeekRel(-10) }, size = 46) {
                Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            GlassIconButton(onClick = onTogglePause, size = 64) {
                Icon(
                    imageVector = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (state.paused) "Play" else "Pause",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            GlassIconButton(onClick = { onSeekRel(10) }, size = 46) {
                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        // Bottom: scrubber + time + audio/sub buttons + codec chips
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            val duration = state.duration.toFloat().coerceAtLeast(1f)
            val position = if (isSeeking) seekPosition else state.position.toFloat()

            // Action row above the scrubber (left = chips, right = pickers)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HdrDisplayChip(state.hdrDisplay)
                    Spacer(Modifier.width(6.dp))
                    state.hwdec?.let { CodecChip(it.uppercase()); Spacer(Modifier.width(6.dp)) }
                    state.videoCodec?.takeIf { it.isNotBlank() }?.let { CodecChip(it.uppercase()) }
                }
                Spacer(modifier = Modifier.width(10.dp))
                GlassIconButton(
                    onClick = { onOpenPicker(PickerKind.Audio) },
                    size = 36,
                    enabled = state.audioTracks.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = "Audio tracks", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                GlassIconButton(
                    onClick = { onOpenPicker(PickerKind.Subtitle) },
                    size = 36,
                    enabled = state.subtitleTracks.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Subtitles, contentDescription = "Subtitles", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                GlassIconButton(
                    onClick = onOpenOnlineSubs,
                    size = 36,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Online subtitles",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                GlassIconButton(
                    onClick = onOpenOptions,
                    size = 36,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Playback options",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Apple TV-style scrubber — fully custom; thin 3dp track
            // centred vertically, 10dp white dot rides the filled edge.
            ApplePillScrubber(
                value = position,
                valueRange = 0f..duration,
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(position.toDouble()),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "-" + formatTime((state.duration - position).coerceAtLeast(0.0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Custom horizontal scrubber. Material3 Slider's thumb/track customisation
 * is fiddly when you want anything smaller than the default chunk — easier
 * to draw a thin track + small dot ourselves and wire the gesture handlers
 * directly. Mirrors Apple TV's playback scrubber where the dot rides the
 * leading edge of the filled portion.
 */
@Composable
private fun ApplePillScrubber(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .height(22.dp)
            .pointerInput(span) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + frac * span)
                    onValueChangeFinished()
                }
            }
            .pointerInput(span) {
                detectHorizontalDragGestures(
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                ) { change, _ ->
                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + frac * span)
                    change.consume()
                }
            },
    ) {
        val widthDp = this.maxWidth
        // Background track
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Color.White.copy(alpha = 0.22f),
                    RoundedCornerShape(2.dp),
                ),
        ) {
            // Filled portion
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
        // Thumb dot — positioned on the leading edge of the fill
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((widthDp * fraction) - 5.dp).roundToPx(), 0) }
                .size(10.dp)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    size: Int,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.White.copy(alpha = if (enabled) 0.12f else 0.05f),
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun HdrDisplayChip(caps: HdrDisplayCapabilities) {
    CodecChip("DISPLAY ${caps.shortLabel}")
}

@Composable
private fun HdrTitleBadge(meta: HdrVideoMetadata) {
    val tag = hdrTitleTag(meta) ?: return
    TechLogoBadge(tag = tag, heightDp = 16)
}

private fun hdrTitleTag(meta: HdrVideoMetadata): TechTag? = when (meta.label) {
    "Dolby Vision" -> TechTag.DolbyVision
    "HDR10+" -> TechTag.HDR10Plus
    "HDR10" -> TechTag.HDR10
    "HLG" -> TechTag.HLG
    "HDR" -> TechTag.HDR
    else -> null
}

@Composable
private fun CodecChip(text: String) {
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = Color.White.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackPickerSheet(
    kind: PickerKind,
    state: MpvPlaybackState,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val tracks = if (kind == PickerKind.Audio) state.audioTracks else state.subtitleTracks
    val currentId = if (kind == PickerKind.Audio) state.currentAudioId else state.currentSubId
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isSubs = kind == PickerKind.Subtitle

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CyferDarkSurface,
        contentColor = CyferWhite,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 560.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isSubs) "Subtitles" else "Audio",
                color = CyferWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            when {
                tracks.isEmpty() -> Text(
                    text = if (isSubs) "No embedded subtitle tracks in this file."
                    else "Only one audio track in this file.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> TwoColumnTrackPane(
                    tracks = tracks,
                    currentId = currentId,
                    includeOff = isSubs,
                    onPick = onPick,
                )
            }
        }
    }
}

/**
 * Two-column track picker — language list on the left, track sources on
 * the right. Mirrors the desktop `popover-cols` layout from
 * `player.tsx`. Tracks are bucketed by `lang`; the right column shows
 * every track in the currently-focused language so the user can pick
 * between e.g. multiple English dubs / SDH passes.
 */
@Composable
private fun TwoColumnTrackPane(
    tracks: List<MpvTrack>,
    currentId: Int,
    includeOff: Boolean,
    onPick: (Int) -> Unit,
) {
    data class LangGroup(val key: String, val display: String, val tracks: List<MpvTrack>)
    val groups = remember(tracks) {
        tracks.groupBy { (it.lang?.lowercase()?.trim()?.takeIf { l -> l.isNotEmpty() && l != "und" } ?: "und") }
            .map { (key, list) ->
                LangGroup(key = key, display = displayLanguage(key), tracks = list)
            }
            .sortedBy { it.display }
    }

    // Default focus: language of the currently-selected track.
    val initialKey = remember(currentId, groups) {
        groups.firstOrNull { g -> g.tracks.any { it.id == currentId } }?.key ?: groups.firstOrNull()?.key
    }
    var selectedLangKey by rememberSaveable(tracks) { mutableStateOf(initialKey) }

    Row(modifier = Modifier.heightIn(min = 240.dp, max = 420.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                text = "LANGUAGE",
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (includeOff) {
                    item("off") {
                        ColumnRow(
                            label = "Off",
                            selected = currentId <= 0,
                            active = selectedLangKey == null,
                            onClick = { selectedLangKey = null; onPick(0) },
                        )
                    }
                }
                items(groups, key = { it.key }) { group ->
                    val isPlaying = group.tracks.any { it.id == currentId }
                    val isFocused = group.key == selectedLangKey
                    ColumnRow(
                        label = group.display,
                        meta = if (group.tracks.size > 1) "${group.tracks.size} sources" else null,
                        selected = isPlaying,
                        active = isFocused,
                        onClick = { selectedLangKey = group.key },
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
            Text(
                text = if (includeOff) "SOURCE" else "SOURCES",
                color = CyferTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            val activeGroup = groups.firstOrNull { it.key == selectedLangKey }
            if (activeGroup == null) {
                Text(
                    text = "Pick a language to see the available tracks.",
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(activeGroup.tracks, key = { it.id }) { t ->
                        ColumnRow(
                            label = trackLabel(t),
                            meta = trackSub(t),
                            selected = t.id == currentId,
                            active = false,
                            onClick = { onPick(t.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnRow(
    label: String,
    meta: String? = null,
    selected: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = when {
            selected -> CyferCardSurfaceLight
            active -> CyferCardSurface
            else -> Color.Transparent
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = CyferWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!meta.isNullOrBlank()) {
                    Text(
                        text = meta,
                        color = CyferTextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = CyferAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private val LANG_ALIASES = mapOf(
    "en" to "English", "eng" to "English",
    "es" to "Spanish", "spa" to "Spanish",
    "fr" to "French", "fre" to "French", "fra" to "French",
    "de" to "German", "ger" to "German", "deu" to "German",
    "it" to "Italian", "ita" to "Italian",
    "pt" to "Portuguese", "por" to "Portuguese",
    "ru" to "Russian", "rus" to "Russian",
    "ja" to "Japanese", "jpn" to "Japanese",
    "ko" to "Korean", "kor" to "Korean",
    "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
    "ar" to "Arabic", "ara" to "Arabic",
    "hi" to "Hindi", "hin" to "Hindi",
    "tr" to "Turkish", "tur" to "Turkish",
    "pl" to "Polish", "pol" to "Polish",
    "nl" to "Dutch", "dut" to "Dutch", "nld" to "Dutch",
    "sv" to "Swedish", "swe" to "Swedish",
    "fi" to "Finnish", "fin" to "Finnish",
    "no" to "Norwegian", "nor" to "Norwegian",
    "da" to "Danish", "dan" to "Danish",
    "cs" to "Czech", "cze" to "Czech", "ces" to "Czech",
    "el" to "Greek", "gre" to "Greek", "ell" to "Greek",
    "he" to "Hebrew", "heb" to "Hebrew",
    "th" to "Thai", "tha" to "Thai",
    "vi" to "Vietnamese", "vie" to "Vietnamese",
    "id" to "Indonesian", "ind" to "Indonesian",
    "ms" to "Malay", "may" to "Malay", "msa" to "Malay",
)

private fun displayLanguage(code: String?): String {
    if (code.isNullOrBlank() || code.equals("und", ignoreCase = true)) return "Unknown"
    LANG_ALIASES[code.lowercase()]?.let { return it }
    return try {
        val locale = java.util.Locale.forLanguageTag(code)
        locale.displayLanguage.takeIf { it.isNotBlank() && it != code } ?: code.uppercase()
    } catch (_: Throwable) {
        code.uppercase()
    }
}

@Composable
private fun TrackRow(
    label: String,
    sub: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) CyferCardSurfaceLight else CyferCardSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) Icons.Filled.Check else Icons.Filled.AudioFile,
                contentDescription = null,
                tint = if (selected) CyferAccent else CyferTextTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = CyferWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!sub.isNullOrBlank()) {
                    Text(
                        text = sub,
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun trackLabel(t: MpvTrack): String {
    val title = t.title?.trim().takeIf { !it.isNullOrEmpty() }
    val lang = t.lang?.trim()?.uppercase()?.takeIf { !it.isNullOrEmpty() && it != "UND" }
    return when {
        title != null && lang != null -> "$title  ·  $lang"
        title != null -> title
        lang != null -> lang
        else -> "Track ${t.id}"
    }
}

private fun trackSub(t: MpvTrack): String? {
    val codec = t.codec?.uppercase()?.takeIf { !it.isNullOrBlank() }
    return codec
}

private fun formatTime(seconds: Double): String {
    val totalSec = seconds.toInt().coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}

// ═══════════════════════════════════════════════════════════════
//  Up Next (Phase 1b autoplay + prefetch)
// ═══════════════════════════════════════════════════════════════

private fun nextEpisodeRequest(
    showTitle: String?,
    tmdbId: Int,
    mediaType: String,
    next: app.cyfer.streaming.android.data.player.NextEpisode,
    backdropUrl: String?,
    posterUrl: String?,
): app.cyfer.streaming.android.ui.sources.SourcePickerRequest =
    app.cyfer.streaming.android.ui.sources.SourcePickerRequest(
        title = showTitle ?: "Episode",
        year = null,
        mediaType = if (mediaType == "anime") app.cyfer.streaming.android.data.torrent.TorrentMediaType.anime
            else app.cyfer.streaming.android.data.torrent.TorrentMediaType.tv,
        season = next.season,
        episode = next.episode,
        episodeTitle = next.name,
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        tmdbId = tmdbId,
    )

@Composable
private fun UpNextCard(
    episode: app.cyfer.streaming.android.data.player.NextEpisode,
    onCancel: () -> Unit,
    onPlayNow: () -> Unit,
) {
    // 10-second auto-advance countdown.
    var secondsLeft by remember(episode.tmdbId, episode.season, episode.episode) { mutableIntStateOf(10) }
    LaunchedEffect(episode.tmdbId, episode.season, episode.episode) {
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            secondsLeft--
        }
        onPlayNow()
    }
    val fraction = (10 - secondsLeft) / 10f

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.85f),
        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "UP NEXT  ·  ${secondsLeft}s",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!episode.stillUrl.isNullOrBlank()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(width = 80.dp, height = 46.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                    ) {
                        coil.compose.AsyncImage(
                            model = episode.stillUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "S${episode.season.toString().padStart(2, '0')} E${episode.episode.toString().padStart(2, '0')}",
                        color = CyferAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = episode.name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            // Slim countdown progress bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(CyferAccent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onCancel,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text = "Cancel",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Surface(
                    onClick = onPlayNow,
                    color = CyferAccent,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Play Now",
                        color = CyferBlack,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Online subtitles sheet (Phase 5)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineSubsSheet(
    imdbId: String?,
    mediaType: String,
    season: Int?,
    episode: Int?,
    onDismiss: () -> Unit,
    onPick: (app.cyfer.streaming.android.data.stremio.ResolvedAddonSubtitle) -> Unit,
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember(imdbId) { mutableStateOf(true) }
    var subs by remember(imdbId, season, episode) {
        mutableStateOf<List<app.cyfer.streaming.android.data.stremio.ResolvedAddonSubtitle>>(emptyList())
    }
    var error by remember(imdbId) { mutableStateOf<String?>(null) }

    LaunchedEffect(imdbId, season, episode) {
        if (imdbId.isNullOrBlank()) {
            error = "No IMDb id for this title — online subtitles need it to query addons. Open the title from Details to get IMDb data."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val settings = app.cyfer.streaming.android.data.settings.SettingsRepository
            .get(ctx).settings.let { flow ->
                var v: app.cyfer.streaming.android.data.settings.AppSettings? = null
                flow.collect { v = it; if (v != null) return@collect }
                v ?: app.cyfer.streaming.android.data.settings.AppSettings()
            }
        runCatching {
            app.cyfer.streaming.android.data.stremio.getAddonSubtitles(
                addons = settings.installedAddons,
                stremioId = imdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
            )
        }
            .onSuccess { subs = it }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CyferDarkSurface,
        contentColor = CyferWhite,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 560.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Online subtitles",
                color = CyferWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            when {
                loading -> {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = CyferAccent) }
                }
                error != null -> Text(error!!, color = CyferError, style = MaterialTheme.typography.bodyMedium)
                subs.isEmpty() -> Text(
                    "No subtitles found. Make sure you have an addon that declares a `subtitles` resource (e.g. OpenSubtitles v3) installed and enabled.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(subs.size, key = { i -> "sub-${subs[i].url.hashCode()}" }) { idx ->
                        val sub = subs[idx]
                        Surface(
                            onClick = { onPick(sub) },
                            color = CyferCardSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sub.label,
                                        color = CyferWhite,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${sub.lang.uppercase()}  ·  ${sub.addonName}",
                                        color = CyferTextTertiary,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Playback options sheet (Phase B — speed + aspect)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackOptionsSheet(
    speed: Float,
    aspect: String,
    subSize: String,
    subPosition: String,
    subBackdrop: Boolean,
    onSpeed: (Float) -> Unit,
    onAspect: (String) -> Unit,
    onSubSize: (String) -> Unit,
    onSubPosition: (String) -> Unit,
    onSubBackdrop: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CyferDarkSurface,
        contentColor = CyferWhite,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Playback options", color = CyferWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SPEED", color = CyferTextTertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { v ->
                        app.cyfer.streaming.android.ui.common.CyferChip(
                            label = if (v == 1f) "1×" else "${v}×",
                            selected = kotlin.math.abs(v - speed) < 0.01f,
                            onClick = { onSpeed(v) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ASPECT RATIO", color = CyferTextTertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf("Default", "16:9", "4:3", "21:9").forEach { v ->
                        app.cyfer.streaming.android.ui.common.CyferChip(
                            label = v,
                            selected = v == aspect,
                            onClick = { onAspect(v) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SUBTITLE SIZE", color = CyferTextTertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf("Small", "Medium", "Large", "Huge").forEach { v ->
                        app.cyfer.streaming.android.ui.common.CyferChip(
                            label = v,
                            selected = v == subSize,
                            onClick = { onSubSize(v) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SUBTITLE POSITION", color = CyferTextTertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Top", "Bottom").forEach { v ->
                        app.cyfer.streaming.android.ui.common.CyferChip(
                            label = v,
                            selected = v == subPosition,
                            onClick = { onSubPosition(v) },
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    app.cyfer.streaming.android.ui.common.CyferChip(
                        label = if (subBackdrop) "Dim On" else "Dim Off",
                        selected = subBackdrop,
                        onClick = { onSubBackdrop(!subBackdrop) },
                    )
                }
            }
        }
    }
}
