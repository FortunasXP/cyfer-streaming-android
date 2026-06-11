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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
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
import app.cyfer.streaming.android.player.MpvChapter
import app.cyfer.streaming.android.player.MpvPlaybackState
import app.cyfer.streaming.android.player.MpvPlayer
import app.cyfer.streaming.android.player.MpvPlayerView
import app.cyfer.streaming.android.player.StreamHealth
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
    var optionsOpen by remember { mutableStateOf(false) }
    // Lock mode: when on, the entire control + gesture layer is sealed
    // off so accidental touches (pocket, in-bed viewing) can't seek or
    // toggle anything. A single tap surfaces a brief unlock affordance.
    var locked by remember { mutableStateOf(false) }
    var unlockHintAt by remember { mutableStateOf(0L) }
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
        // Stop scrobble on dispose — Trakt marks watched at ≥80%. Uses
        // the repository's own scope: the composition scope is being
        // cancelled at this exact moment, so launching on it silently
        // dropped the stop (and with it the watched mark).
        DisposableEffect(Unit) {
            onDispose {
                if (startedScrobble) {
                    traktRepo.scrobbleAsync(
                        app.cyfer.streaming.android.data.trakt.TraktRepository.ScrobbleAction.Stop,
                        imdbId, currentPct(), season, episode,
                    )
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
        // Track the compositor's HDR headroom live (A14+). The detect()
        // snapshot is taken before any HDR frame exists, so it always
        // reads 1.00x — the listener catches SurfaceFlinger raising the
        // ratio once our PQ output actually starts being displayed.
        val stopHdrRatio = HdrDisplayDetector.listenHdrSdrRatio(context) { ratio ->
            MpvPlayer.updateHdrSdrRatio(ratio)
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            stopHdrRatio?.invoke()
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
    // Track-selection memory: feed the persisted language preferences
    // into mpv's alang/slang so every load auto-picks the user's last
    // manually chosen audio/subtitle language (desktop parity).
    LaunchedEffect(playerSettings.preferredAudioLanguage, playerSettings.preferredSubtitleLanguage) {
        MpvPlayer.applyTrackPreferences(
            audioLang = playerSettings.preferredAudioLanguage,
            subLang = playerSettings.preferredSubtitleLanguage,
        )
    }
    // Reactive HDR pipeline — flips libplacebo's tone-mapping, gamut
    // mapping, SDR target peak, and forced-HDR override as the user
    // tweaks them in Settings. Re-fires applyHdrOptions() so the live
    // render picks up the new curve without a reload.
    LaunchedEffect(
        playerSettings.toneMappingAlgorithm,
        playerSettings.gamutMappingMode,
        playerSettings.sdrTargetPeakNits,
        playerSettings.forceHdrOutput,
        playerSettings.forcedHdrPeakNits,
    ) {
        MpvPlayer.applyHdrConfig(
            app.cyfer.streaming.android.player.MpvPlayer.HdrPipelineConfig(
                toneMapping = playerSettings.toneMappingAlgorithm.mpvOption(),
                gamutMappingMode = playerSettings.gamutMappingMode.mpvOption(),
                sdrTargetPeakNits = playerSettings.sdrTargetPeakNits,
                forceHdrOutput = playerSettings.forceHdrOutput,
                forcedHdrPeakNits = playerSettings.forcedHdrPeakNits,
            ),
        )
    }

    LaunchedEffect(url) { MpvPlayer.loadUrl(url, title) }

    // ── First-frame watchdog (HW → HW-copy → report) ────────────────
    // Some chipsets accept a stream then silently produce no frames on
    // DV-tagged HEVC / 4K AV1.
    //
    // Cascade:
    //   t = 0    loadfile with the user's chosen hwdec (usually mediacodec)
    //   t = 12s  if position still 0 → switch to mediacodec-copy + reload
    //   t = 24s  if position still 0 → report decoder failure
    //
    // There is deliberately NO software-decode stage: SW 4K HEVC on a
    // phone SoC is unplayable, so the honest endpoint is telling the
    // user this file's encode can't be hardware-decoded here — pick a
    // different source from the picker.
    var copySwapTried by remember(url) { mutableStateOf(false) }
    var decoderFailReported by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        delay(12_000)
        if (!copySwapTried && !playbackState.idle && playbackState.position < 0.05) {
            copySwapTried = true
            MpvPlayer.switchToMediacodecCopy()
        }
    }
    LaunchedEffect(url, copySwapTried) {
        if (!copySwapTried) return@LaunchedEffect
        delay(12_000)
        if (!decoderFailReported && !playbackState.idle && playbackState.position < 0.05) {
            decoderFailReported = true
            MpvPlayer.markDecoderFailed()
        }
    }

    // ── Stall watchdog (auto-retry on dead source) ───────────────────
    // Watches demuxer-cache-time. If it sits at 0 for >15 s while
    // !paused with a non-zero position, save the spot and reload the
    // URL. mpv's "keep-open" + our network-timeout would otherwise just
    // hang. Capped at 3 retries per URL so a truly dead source eventually
    // gives up instead of looping.
    var stallRetries by remember(url) { mutableStateOf(0) }
    var pendingResumeSec by remember(url) { mutableStateOf(0.0) }
    LaunchedEffect(url) {
        var stallSince: Long = 0
        while (true) {
            delay(2_000)
            val s = MpvPlayer.state.value
            if (s.idle || s.paused) { stallSince = 0; continue }
            if (s.position <= 0.05) { stallSince = 0; continue }
            if (s.demuxerCacheSeconds > 0.5) {
                stallSince = 0
                if (s.streamHealth == StreamHealth.Buffering || s.streamHealth == StreamHealth.Stalled) {
                    MpvPlayer.clearStreamHealth()
                }
                continue
            }
            // Cache empty — track how long.
            val now = System.currentTimeMillis()
            if (stallSince == 0L) stallSince = now
            val elapsed = now - stallSince
            if (elapsed in 3_000..14_999 && s.streamHealth == StreamHealth.Normal) {
                // Soft state — UI shows "Buffering…"
                MpvPlayer.state.value.let { /* no-op state read for compiler */ }
                // Set via the helper — we can't reach into _state here.
                // Treat Buffering as transient; if it clears we go back
                // to Normal in the cache>0.5 branch above.
            }
            if (elapsed >= 15_000 && stallRetries < 3) {
                stallRetries += 1
                pendingResumeSec = s.position
                android.util.Log.i("PlayerScreen", "Stall recovery #$stallRetries at ${pendingResumeSec}s")
                MpvPlayer.reloadCurrent()
                stallSince = 0
            }
        }
    }

    // After a recovery reload, seek back to where we were once playback
    // ticks past 0 again.
    LaunchedEffect(playbackState.position, pendingResumeSec) {
        if (pendingResumeSec > 0 && playbackState.position in 0.01..pendingResumeSec - 0.1) {
            val target = pendingResumeSec
            pendingResumeSec = 0.0
            MpvPlayer.seekTo(target)
            MpvPlayer.clearStreamHealth()
        }
    }

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
        // Disabled when locked: the lock overlay below handles taps so
        // the user can surface an Unlock affordance, but nothing else.
        if (!inPip && !locked) {
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

        // ── HDR diagnostic overlay ─────────────────────────────────
        // Toggled from Settings → Playback → HDR pipeline. Bottom-left,
        // ignored when chrome is showing so it doesn't overlap controls.
        if (playerSettings.hdrDiagnosticOverlay && !inPip && !isLoading && !controlsVisible && !locked) {
            HdrDiagnosticOverlay(
                state = playbackState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, bottom = 24.dp),
            )
        }

        // ── Stream-health toast ────────────────────────────────────
        // Surfaces the auto-retry / SW-fallback work happening behind
        // the scenes so the user understands a stutter or reload isn't
        // the app being broken. Hidden when everything is normal.
        AnimatedVisibility(
            visible = playbackState.streamHealth != StreamHealth.Normal && !inPip && !isLoading,
            enter = fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp),
        ) {
            StreamHealthToast(health = playbackState.streamHealth)
        }

        // One-shot Dolby Vision P5 warning. Hardware decode renders the
        // P5 base layer without the RPU reshape (FFmpeg's mediacodec
        // wrapper doesn't parse RPUs), and a P5 base layer is IPTPQc2 —
        // i.e. visibly wrong colours. No player setting can fix it, so
        // point the user at the actual remedy: an HDR10 / DV P8 source.
        // Gated on position > 0 so colormatrix has settled (a reshaped
        // SW-decoded file reports colormatrix=dolbyvision by then).
        var dvWarningDismissed by remember(url) { mutableStateOf(false) }
        val showDvWarning = !dvWarningDismissed &&
            playbackState.hdrVideo.dolbyVisionP5BaseLayer &&
            playbackState.position > 0 &&
            playbackState.streamHealth == StreamHealth.Normal &&
            !inPip && !isLoading
        LaunchedEffect(showDvWarning) {
            if (showDvWarning) {
                delay(8000)
                dvWarningDismissed = true
            }
        }
        AnimatedVisibility(
            visible = showDvWarning,
            enter = fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.78f),
            ) {
                Text(
                    text = "⚠ Dolby Vision P5 — colours can't be decoded correctly on this device. Pick an HDR10 or DV P8 source.",
                    color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Apple TV-style controls overlay (hidden in PiP and when locked)
        AnimatedVisibility(visible = controlsVisible && !isLoading && !inPip && !locked, enter = fadeIn(), exit = fadeOut()) {
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
                onSeekChange = { v ->
                    // Live-scrub: snap MPV to the dragged position so the
                    // main video surface IS the thumbnail preview. Uses
                    // keyframe-accurate seek for speed; the final
                    // commit on release uses the precise seek.
                    isSeeking = true
                    seekPosition = v
                    MpvPlayer.scrubTo(v.toDouble())
                },
                onSeekFinished = {
                    MpvPlayer.seekTo(seekPosition.toDouble())
                    isSeeking = false
                },
                onOpenPicker = { pickerOpen = it },
                onEnterPip = { enterPip() },
                onOpenOptions = { optionsOpen = true },
                onLock = {
                    locked = true
                    controlsVisible = false
                },
            )
        }

        // ── Lock overlay ───────────────────────────────────────────
        // While locked: full-screen tap-catcher swallows every touch so
        // no gesture / pickup misfire reaches the player. A single tap
        // briefly surfaces an Unlock pill; the user must tap that pill
        // to come back out of lock mode.
        if (locked && !inPip) {
            val now = System.currentTimeMillis()
            val showHint = (now - unlockHintAt) < 2_500L
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { unlockHintAt = System.currentTimeMillis() }
                    },
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showHint,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .statusBarsPadding(),
                ) {
                    Surface(
                        onClick = { locked = false; controlsVisible = true },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LockOpen,
                                contentDescription = "Unlock",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Tap to unlock",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
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
                        PickerKind.Audio -> {
                            MpvPlayer.setAudioTrack(id)
                            // Remember the language so subsequent files
                            // auto-select it (mpv alang). Tracks with no
                            // language tag (e.g. "Commentary") don't
                            // overwrite the stored preference.
                            playbackState.audioTracks.firstOrNull { it.id == id }?.lang
                                ?.takeIf { it.isNotBlank() }
                                ?.let { lang ->
                                    libScope.launch {
                                        settingsRepo.update { it.copy(preferredAudioLanguage = lang) }
                                    }
                                }
                        }
                        PickerKind.Subtitle -> {
                            MpvPlayer.setSubtitleTrack(id)
                            // "Off" (id 0) is remembered too — subs stay
                            // disabled on the next file until the user
                            // picks a language again.
                            val pref = if (id <= 0) "off"
                            else playbackState.subtitleTracks.firstOrNull { it.id == id }?.lang
                                ?.takeIf { it.isNotBlank() }
                            pref?.let { p ->
                                libScope.launch {
                                    settingsRepo.update { it.copy(preferredSubtitleLanguage = p) }
                                }
                            }
                        }
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
    onOpenOptions: () -> Unit,
    onLock: () -> Unit,
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
                // One quiet format badge next to the title (DV / HDR10 /
                // HLG logo). Profile + reshape detail lives in the HDR
                // diagnostic overlay — the chrome stays Apple TV-clean.
                if (state.hdrVideo.active) {
                    Spacer(modifier = Modifier.width(8.dp))
                    HdrTitleBadge(state.hdrVideo)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            GlassIconButton(onClick = onLock, size = 38) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Lock controls",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
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
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The single dynamic truth worth keeping in the
                    // chrome: HDR output is live, and at what peak. The
                    // static display-caps / hwdec / codec-name chips
                    // moved to the diagnostic overlay — they never
                    // change mid-session and just cluttered the chrome.
                    if (state.hdrPipelineActive) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                        ) {
                            val luminanceTag = state.targetLuminanceNits
                                .takeIf { it > 0 }
                                ?.let { " · ${it.toInt()} NIT" }
                                .orEmpty()
                            Text(
                                text = "HDR$luminanceTag",
                                color = Color.Black,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
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

            // Skip pill — surfaces when libmpv's chapter-list says we're
            // currently in an intro / opening / outro / ending / recap
            // chapter. Only uses real chapter metadata, never guesses.
            val skipTarget = remember(position, state.chapters) {
                classifySkipTarget(position.toDouble(), state.chapters)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = skipTarget != null,
                enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it / 2 },
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.End).padding(bottom = 10.dp, end = 2.dp),
            ) {
                skipTarget?.let { tgt ->
                    Surface(
                        onClick = {
                            val seekTo = tgt.endTime.takeIf { it > 0 } ?: state.duration
                            if (seekTo > 0) onSeekChange(seekTo.toFloat())
                            onSeekFinished()
                        },
                        shape = RoundedCornerShape(22.dp),
                        color = Color.White,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = tgt.label,
                                color = Color.Black,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Buffered-ahead fraction: where the demuxer cache extends to,
            // expressed as a 0..1 fraction of total duration. Surfaced as
            // a dimmer fill on the scrubber so the user can see why a
            // stream stalls (cache hit zero) and how much head-room they
            // have on a fast connection.
            val bufferedAheadFraction = remember(state.position, state.demuxerCacheSeconds, state.duration) {
                if (state.duration > 0) {
                    ((state.position + state.demuxerCacheSeconds) / state.duration)
                        .toFloat()
                        .coerceIn(0f, 1f)
                } else 0f
            }
            // Chapter tick marks — only render when there's more than one
            // chapter (single-chapter files just have a meaningless tick
            // at 0).
            val chapterFractions = remember(state.chapters, state.duration) {
                if (state.chapters.size <= 1 || state.duration <= 0) emptyList()
                else state.chapters.drop(1).map { (it.time / state.duration).toFloat().coerceIn(0f, 1f) }
            }

            // Apple TV-style scrubber — fully custom; thin 3dp track
            // centred vertically, 10dp white dot rides the filled edge.
            ApplePillScrubber(
                value = position,
                valueRange = 0f..duration,
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekFinished,
                bufferedFraction = bufferedAheadFraction,
                chapterFractions = chapterFractions,
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
    /** 0..1 — how far the demuxer cache extends. Drawn as a dim fill
     *  past the current position so the user sees how much head-room
     *  they have on a streaming source. */
    bufferedFraction: Float = 0f,
    /** Fractional positions (0..1) of chapter starts. Drawn as small
     *  tick marks on the scrubber so the user can see where intros /
     *  outros / acts begin. */
    chapterFractions: List<Float> = emptyList(),
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val bufferedClamped = bufferedFraction.coerceIn(0f, 1f)

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
            // Buffered ahead — dim fill that runs from the start to the
            // demuxer cache horizon. Drawn before the played portion so
            // the bright fill sits on top in the played zone.
            if (bufferedClamped > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(bufferedClamped)
                        .background(
                            Color.White.copy(alpha = 0.45f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            // Filled portion (played)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
        // Chapter tick marks — 2 dp wide, 8 dp tall, slightly transparent.
        chapterFractions.forEach { f ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((widthDp * f) - 1.dp).roundToPx(), 0) }
                    .width(2.dp)
                    .height(8.dp)
                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(1.dp)),
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

/** Result of classifying a chapter the user is currently inside, when
 *  it matches the intro/outro/recap/preview pattern. */
private data class SkipTarget(val label: String, val endTime: Double)

/**
 * Classify the chapter at [positionSec] against libmpv's chapter list.
 * Returns a [SkipTarget] when the title clearly indicates an intro,
 * outro, opening, ending, recap, credits, or preview — strictly
 * keyword-based on the chapter title, never a heuristic.
 */
private fun classifySkipTarget(positionSec: Double, chapters: List<MpvChapter>): SkipTarget? {
    if (chapters.isEmpty()) return null
    val currentIdx = chapters.indexOfLast { it.time <= positionSec }
    if (currentIdx < 0) return null
    val current = chapters[currentIdx]
    val title = current.title?.trim().orEmpty().lowercase()
    if (title.isEmpty()) return null

    val label = when {
        // Anime opening / English "intro" / numbered openings ("op1", "op v2")
        title == "op" || title.startsWith("op ") || title.startsWith("op:") ||
            title.contains("opening") || title.contains("intro") -> "Skip Intro"
        // Anime ending / English "outro" / numbered ED
        title == "ed" || title.startsWith("ed ") || title.startsWith("ed:") ||
            title.contains("ending") || title.contains("outro") -> "Skip Outro"
        title.contains("credit") -> "Skip Credits"
        title.contains("recap") || title.contains("previously") -> "Skip Recap"
        title.contains("preview") || title.contains("next time") -> "Skip Preview"
        else -> return null
    }
    val nextTime = chapters.getOrNull(currentIdx + 1)?.time ?: -1.0
    return SkipTarget(label, nextTime)
}

/**
 * Compact monospace overlay that lists the HDR pipeline state at a
 * glance — source primaries/transfer/peak, the planner's decision, the
 * render-target transfer + peak that libplacebo actually negotiated
 * (`video-target-params`), and the DV reshape state. Toggle via
 * Settings → HDR.
 */
@Composable
private fun HdrDiagnosticOverlay(state: MpvPlaybackState, modifier: Modifier = Modifier) {
    val hdr = state.hdrVideo
    val display = state.hdrDisplay
    val rows = buildList {
        add("DISPLAY    " + display.shortLabel + if (state.hdrPipelineActive) " · HDR OUT" else " · sdr-out")
        // What the swapchain actually accepted — the ground truth the
        // old hdr-display-detected property pretended to be.
        add("TARGET     " + state.targetTransfer.ifBlank { "—" } +
            (state.targetLuminanceNits.takeIf { it > 0 }?.let { " · ${it.toInt()} nit" } ?: ""))
        // The content-format × display-capability decision the planner
        // made for this file — the single most useful line here.
        if (state.outputPlanDescription.isNotBlank()) {
            add("PLAN       " + state.outputPlanDescription)
        }
        // GL-stack capability: framebuffer depth + whether the EGL
        // driver offers the BT.2020-PQ colorspace extension. "pq ext no"
        // means the vendor blobs can't signal HDR — hardware floor.
        if (state.eglSummary.isNotBlank()) {
            add("EGL        " + state.eglSummary)
        }
        // Android-14+ system HDR conversion mode — if SystemTonemap the OS
        // is silently mapping our HDR back to SDR regardless of what we
        // send. Most useful diagnostic on a stuck-in-SDR device.
        if (display.systemHdrConversion != app.cyfer.streaming.android.player.HdrSystemConversionMode.Unsupported &&
            display.systemHdrConversion != app.cyfer.streaming.android.player.HdrSystemConversionMode.Unknown) {
            add("SYS HDR    " + display.systemHdrConversion.name.lowercase())
        }
        display.hdrSdrRatio?.let { ratio ->
            add("HDR/SDR    " + "%.2fx".format(ratio))
        }
        add("SOURCE     " + (hdr.detailedLabel))
        add("PRIM/TRC   " + listOfNotNull(hdr.primaries, hdr.transfer).joinToString(" / ").ifBlank { "—" })
        // Colour-correctness essentials: range tagging (limited vs full
        // mix-ups = washed or crushed blacks) and the decoder's actual
        // output format (nv12 on 10-bit content = depth silently lost).
        add("RANGE/FMT  " + listOfNotNull(
            hdr.colorLevels,
            hdr.hwPixelFormat ?: hdr.pixelFormat,
        ).joinToString(" · ").ifBlank { "—" })
        add("SIG PEAK   " + (hdr.signalPeak?.let { "%.1f".format(it) } ?: "—") +
            "  CLL " + (hdr.maxCll?.toInt()?.toString() ?: "—") +
            "  FALL " + (hdr.maxFall?.toInt()?.toString() ?: "—"))
        hdr.dolbyVisionProfile?.let { p ->
            val reshape = when {
                hdr.dolbyVisionReshapeActive -> "reshape=on"
                hdr.dolbyVisionP5BaseLayer -> "reshape=OFF (P5 BL — wrong colours)"
                else -> "reshape=off (HDR10 BL)"
            }
            add("DV         profile=$p" + (hdr.dolbyVisionLevel?.let { " level=$it" } ?: "") + " · " + reshape)
        }
        state.hwdec?.takeIf { it.isNotBlank() }?.let { add("HWDEC      $it") }
        state.videoCodec?.takeIf { it.isNotBlank() }?.let { add("CODEC      $it") }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.74f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "HDR PIPELINE",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            rows.forEach { line ->
                Text(
                    text = line,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Top-center toast surfaced by the stream-health watchdogs. Uses a
 * neutral pill rather than a banner so it doesn't fight the chrome.
 * Reconnecting / SoftwareFallback are useful signals; Buffering is too
 * noisy to surface explicitly (the buffered range on the scrubber
 * already communicates that).
 */
@Composable
private fun StreamHealthToast(health: StreamHealth) {
    val (label, accent) = when (health) {
        StreamHealth.Recovering -> "Reconnecting…" to androidx.compose.ui.graphics.Color(0xFFFFA000)
        StreamHealth.HardwareCopyFallback -> "Switching decoder mode…" to androidx.compose.ui.graphics.Color(0xFF26C6DA)
        StreamHealth.DecoderFailed -> "Can't hardware-decode this file — try another source" to androidx.compose.ui.graphics.Color(0xFFEF5350)
        StreamHealth.Stalled -> "Stream stalled" to androidx.compose.ui.graphics.Color(0xFFEF5350)
        StreamHealth.Buffering -> "Buffering…" to androidx.compose.ui.graphics.Color(0xFF9E9E9E)
        StreamHealth.Normal -> return
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.78f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 1.6.dp,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
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

            // ── Now playing — live stats (desktop PlayerStats parity).
            //    Polled once a second while the sheet is open; reading a
            //    handful of properties on demand beats observing them
            //    for the whole session.
            val pbState by MpvPlayer.state.collectAsStateWithLifecycle()
            var stats by remember { mutableStateOf(MpvPlayer.PlaybackStats()) }
            LaunchedEffect(Unit) {
                while (true) {
                    stats = MpvPlayer.readPlaybackStats()
                    delay(1000)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("NOW PLAYING", color = CyferTextTertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                val rows = buildList {
                    val video = listOfNotNull(
                        pbState.videoCodec?.takeIf { it.isNotBlank() }?.substringBefore(" ")?.uppercase(),
                        stats.videoSize,
                        stats.fps?.let { "%.3f fps".format(it) },
                        stats.videoBitrateMbps?.takeIf { it > 0 }?.let { "%.1f Mbps".format(it) },
                    )
                    if (video.isNotEmpty()) add("VIDEO   " + video.joinToString(" · "))
                    val audio = listOfNotNull(
                        pbState.audioCodec?.takeIf { it.isNotBlank() }?.uppercase(),
                        stats.channelLayout,
                        stats.sampleRateHz?.let { "${it / 1000} kHz" },
                        stats.audioBitrateKbps?.takeIf { it > 0 }?.let { "${it.toInt()} kbps" },
                    )
                    if (audio.isNotEmpty()) add("AUDIO   " + audio.joinToString(" · "))
                    val health = listOfNotNull(
                        stats.avSyncSeconds?.let { "sync %+.0f ms".format(it * 1000) },
                        stats.droppedFrames?.takeIf { it > 0 }?.let { "$it dropped" },
                        "cache ${pbState.demuxerCacheSeconds.toInt()}s",
                        pbState.hwdec?.takeIf { it.isNotBlank() },
                    )
                    add("HEALTH  " + health.joinToString(" · "))
                }
                rows.forEach { line ->
                    Text(
                        text = line,
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
