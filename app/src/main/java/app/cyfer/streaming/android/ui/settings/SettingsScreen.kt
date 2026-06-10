package app.cyfer.streaming.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.settings.AppSettings
import app.cyfer.streaming.android.data.settings.HardwareDecodingMode
import app.cyfer.streaming.android.data.settings.SettingsRepository
import android.content.Intent
import android.net.Uri
import app.cyfer.streaming.android.data.stremio.AddonPreset
import app.cyfer.streaming.android.data.stremio.AddonPresets
import app.cyfer.streaming.android.data.stremio.InstalledAddon
import app.cyfer.streaming.android.data.stremio.fetchAndParseManifest
import app.cyfer.streaming.android.data.stremio.getAddonStreams
import app.cyfer.streaming.android.ui.common.CyferTabPill
import app.cyfer.streaming.android.ui.common.CyferTagPill
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import app.cyfer.streaming.android.ui.theme.*
import kotlinx.coroutines.launch

private enum class SettingsTab(val label: String) {
    Playback("Playback"),
    Resolvers("Resolvers"),
    Sources("Sources"),
    Addons("Addons"),
    Sync("Sync"),
    General("General"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.Playback) }
    var showAddAddonDialog by remember { mutableStateOf(false) }

    fun mutate(transform: (AppSettings) -> AppSettings) {
        scope.launch { repo.update(transform) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CyferBlack)
            .padding(top = 48.dp),
    ) {
        // Title (pinned)
        Text(
            text = "Settings",
            color = CyferWhite,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Horizontal pill-tab row — mirrors the desktop's left-rail nav,
        // collapsed to a scrolling chip strip for narrow screens.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTab.values().forEach { tab ->
                CyferTabPill(
                    label = tab.label,
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Scrolling content for the selected tab.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            when (selectedTab) {
                SettingsTab.Playback -> {
                    SettingsSection(title = "Hardware decoding") {
                        Text(
                            text = "Switch modes only if a stream stutters or won't play.",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HardwareDecodingPicker(
                            current = settings.hardwareDecoding,
                            onPick = { mode -> mutate { it.copy(hardwareDecoding = mode) } },
                        )
                    }
                    SettingsSection(title = "HDR pipeline") {
                        Text(
                            text = "Defaults look right on most phones — only tweak if a title looks off.",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // What the OS reports about this panel. Content is
                        // matched against these formats at play time: DV /
                        // HDR10 sources emit PQ on HDR panels, HLG emits HLG
                        // natively, everything tone-maps down on SDR panels.
                        val displayCtx = androidx.compose.ui.platform.LocalContext.current
                        val displayCaps = remember {
                            app.cyfer.streaming.android.player.HdrDisplayDetector.detect(displayCtx)
                        }
                        Surface(
                            color = CyferCardSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text(
                                    text = "Your display",
                                    color = CyferWhite,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val capsLine = buildString {
                                    if (displayCaps.hdrCapable) {
                                        append("Supports ")
                                        append(displayCaps.orderedFormats.joinToString(" · ") { it.label })
                                        displayCaps.desiredMaxLuminance?.let {
                                            append("  ·  peak ≈${it.toInt()} nits")
                                        }
                                    } else {
                                        append("SDR only — HDR and Dolby Vision content is tone-mapped down")
                                        if (displayCaps.wideColorGamut) append(" (wide colour gamut)")
                                    }
                                }
                                Text(
                                    text = capsLine,
                                    color = CyferTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        EnumPickerRow(
                            label = "Tone mapping algorithm",
                            description = "How HDR highlights compress to your panel's range.",
                            options = listOf(
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.AUTO to "Auto (BT.2390)",
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.BT2390 to "BT.2390",
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.BT2446A to "BT.2446A",
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.HABLE to "Hable (filmic)",
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.MOBIUS to "Mobius",
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.SPLINE to "Spline",
                                app.cyfer.streaming.android.data.settings.ToneMappingAlgorithm.CLIP to "Clip (hard)",
                            ),
                            current = settings.toneMappingAlgorithm,
                            onPick = { v -> mutate { it.copy(toneMappingAlgorithm = v) } },
                        )
                        EnumPickerRow(
                            label = "Gamut mapping",
                            description = "How wide-gamut colour fits the panel. Perceptual preserves hue.",
                            options = listOf(
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.PERCEPTUAL to "Perceptual (recommended)",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.RELATIVE to "Relative",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.SATURATION to "Saturation",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.DESATURATE to "Desaturate",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.DARKEN to "Darken",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.HIGHLIGHT to "Highlight (debug)",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.ABSOLUTE to "Absolute",
                                app.cyfer.streaming.android.data.settings.GamutMappingMode.LINEAR to "Linear",
                            ),
                            current = settings.gamutMappingMode,
                            onPick = { v -> mutate { it.copy(gamutMappingMode = v) } },
                        )
                        IntSliderRow(
                            label = "SDR target peak",
                            description = "Brightness your screen can hit in SDR. Reference is 203.",
                            range = 100..1000,
                            step = 25,
                            current = settings.sdrTargetPeakNits,
                            valueLabel = { "$it nits" },
                            onChange = { v -> mutate { it.copy(sdrTargetPeakNits = v) } },
                        )
                        // Dolby Vision is not user-configurable on this
                        // pipeline: hardware decoding renders the base
                        // layer (P8 = genuine HDR10/HLG), and the libdovi
                        // reshape kicks in automatically whenever frames
                        // carry RPU metadata. The old Auto/Strip picker
                        // controlled an FFmpeg option that doesn't exist.
                        Text(
                            text = "Dolby Vision P8 plays as HDR10/HLG. P5 isn't supported — pick an HDR10 or P8 source.",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Force-HDR is only offered where it can actually
                        // work: the OS claims SDR (the misreporting-ROM
                        // case the toggle exists for) AND the GL driver
                        // has an HDR-capable swapchain. On an honest HDR
                        // display the auto path is strictly better; on a
                        // truly SDR GL stack forcing just dims the
                        // picture. The planner enforces the same gate, so
                        // a stale persisted "on" is ignored too.
                        val eglHdrPossible = remember {
                            app.cyfer.streaming.android.player.EglHdrProbe.probe().hdrOutputPossible
                        }
                        if (!displayCaps.hdrCapable && eglHdrPossible) {
                            ToggleRow(
                                label = "Force HDR output",
                                description = "Android claims this display is SDR, but the graphics driver can output HDR — flip this if you know the panel is really HDR (common on custom ROMs). Looks dim if it truly isn't.",
                                checked = settings.forceHdrOutput,
                                onCheckedChange = { v -> mutate { it.copy(forceHdrOutput = v) } },
                            )
                            if (settings.forceHdrOutput) {
                                IntSliderRow(
                                    label = "Forced HDR peak",
                                    description = "Match your panel's real peak brightness.",
                                    range = 200..2000,
                                    step = 50,
                                    current = settings.forcedHdrPeakNits,
                                    valueLabel = { "$it nits" },
                                    onChange = { v -> mutate { it.copy(forcedHdrPeakNits = v) } },
                                )
                            }
                        }
                        ToggleRow(
                            label = "HDR diagnostic overlay",
                            description = "Live pipeline readout on the player — source, plan, negotiated output, DV reshape.",
                            checked = settings.hdrDiagnosticOverlay,
                            onCheckedChange = { v -> mutate { it.copy(hdrDiagnosticOverlay = v) } },
                        )
                    }
                    SettingsSection(title = "Local torrent fallback") {
                        ToggleRow(
                            label = "Use on-device torrent engine",
                            description = "If Real-Debrid / TorBox can't return a stream (or aren't configured), play magnets through Cyfer's built-in libtorrent engine.",
                            checked = settings.debridFallbackToLocalTorrent,
                            onCheckedChange = { v -> mutate { it.copy(debridFallbackToLocalTorrent = v) } },
                        )
                    }
                    SettingsSection(title = "Up Next") {
                        ToggleRow(
                            label = "Auto-play next episode",
                            description = "When you reach ~95% of a TV or anime episode, show an Up Next card and auto-advance after a 10-second countdown.",
                            checked = settings.autoplayNextEpisode,
                            onCheckedChange = { v -> mutate { it.copy(autoplayNextEpisode = v) } },
                        )
                        ToggleRow(
                            label = "Prefetch next episode sources",
                            description = "At ~50% played, quietly warm the source-picker cache for the next episode so it opens instantly. Costs a little extra metadata bandwidth.",
                            checked = settings.prefetchNextEpisode,
                            onCheckedChange = { v -> mutate { it.copy(prefetchNextEpisode = v) } },
                        )
                    }
                    SettingsSection(title = "Notifications") {
                        val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                            onResult = { /* the worker re-checks before firing */ },
                        )
                        ToggleRow(
                            label = "New-episode notifications",
                            description = "Background job runs every ~12 hours, pings you when a watchlist show drops a new episode in the next 24 hours.",
                            checked = settings.episodeNotificationsEnabled,
                            onCheckedChange = { v ->
                                mutate { it.copy(episodeNotificationsEnabled = v) }
                                if (v && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    val granted = androidx.core.app.ActivityCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.POST_NOTIFICATIONS,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (!granted) notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                }

                SettingsTab.Resolvers -> {
                    SettingsSection(title = "Real-Debrid") {
                        ToggleRow(
                            label = "Enable Real-Debrid",
                            description = "Resolve magnets through Real-Debrid into a direct stream.",
                            checked = settings.realDebridEnabled,
                            onCheckedChange = { v -> mutate { it.copy(realDebridEnabled = v) } },
                        )
                        SecretField(
                            label = "API token",
                            value = settings.realDebridApiToken,
                            onValueChange = { v -> mutate { it.copy(realDebridApiToken = v) } },
                            hint = "real-debrid.com/apitoken",
                        )
                    }
                    SettingsSection(title = "TorBox") {
                        ToggleRow(
                            label = "Enable TorBox",
                            description = "Use TorBox as an additional debrid resolver.",
                            checked = settings.torboxEnabled,
                            onCheckedChange = { v -> mutate { it.copy(torboxEnabled = v) } },
                        )
                        SecretField(
                            label = "API token",
                            value = settings.torboxApiToken,
                            onValueChange = { v -> mutate { it.copy(torboxApiToken = v) } },
                            hint = "torbox.app/settings",
                        )
                    }
                }

                SettingsTab.Sources -> {
                    SettingsSection(title = "Torrent providers") {
                        ToggleRow(
                            label = "Search public torrent providers",
                            description = "Master switch for all torrent search providers below.",
                            checked = settings.torrentSourcesEnabled,
                            onCheckedChange = { v -> mutate { it.copy(torrentSourcesEnabled = v) } },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        settings.torrentSourceProviders.forEach { provider ->
                            ProviderRow(
                                label = provider.name,
                                baseUrl = provider.baseUrl,
                                checked = provider.enabled,
                                enabled = settings.torrentSourcesEnabled,
                                onCheckedChange = { v ->
                                    mutate { current ->
                                        current.copy(
                                            torrentSourceProviders = current.torrentSourceProviders.map { p ->
                                                if (p.id == provider.id) p.copy(enabled = v) else p
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                SettingsTab.Addons -> {
                    // Per-addon status feedback (success / error message) so
                    // refresh/test/install actions don't fail silently.
                    val addonStatus = remember { mutableStateMapOf<String, AddonStatus>() }
                    SettingsSection(title = "Stremio addons") {
                        if (settings.installedAddons.isEmpty()) {
                            Text(
                                text = "No addons installed. Pick one from below or paste a manifest URL.",
                                color = CyferTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            settings.installedAddons.forEach { addon ->
                                AddonRow(
                                    addon = addon,
                                    status = addonStatus[addon.transportUrl],
                                    onToggle = { v ->
                                        mutate { current ->
                                            current.copy(
                                                installedAddons = current.installedAddons.map { a ->
                                                    if (a.transportUrl == addon.transportUrl) a.copy(enabled = v) else a
                                                },
                                            )
                                        }
                                    },
                                    onRefresh = {
                                        addonStatus[addon.transportUrl] = AddonStatus.Working("Refreshing…")
                                        scope.launch {
                                            runCatching { fetchAndParseManifest(addon.transportUrl) }
                                                .onSuccess { fresh ->
                                                    repo.update { current ->
                                                        current.copy(
                                                            installedAddons = current.installedAddons.map { a ->
                                                                if (a.transportUrl == addon.transportUrl)
                                                                    fresh.copy(enabled = a.enabled)
                                                                else a
                                                            },
                                                        )
                                                    }
                                                    addonStatus[addon.transportUrl] = AddonStatus.Ok("Refreshed · v${fresh.version.ifBlank { "?" }}")
                                                }
                                                .onFailure { err ->
                                                    addonStatus[addon.transportUrl] = AddonStatus.Error(err.message ?: err.toString())
                                                }
                                        }
                                    },
                                    onTest = {
                                        addonStatus[addon.transportUrl] = AddonStatus.Working("Testing…")
                                        scope.launch {
                                            // Interstellar (tt0816692) — known IMDb id every stream
                                            // addon should return results for if it's working.
                                            runCatching {
                                                getAddonStreams(
                                                    addons = listOf(addon),
                                                    stremioId = "tt0816692",
                                                    mediaType = "movie",
                                                )
                                            }
                                                .onSuccess { result ->
                                                    val outcome = result.outcomes.firstOrNull()
                                                    addonStatus[addon.transportUrl] = when {
                                                        outcome?.error != null -> AddonStatus.Error(outcome.error)
                                                        result.streams.isEmpty() -> AddonStatus.Error("Returned 0 streams for test title.")
                                                        else -> AddonStatus.Ok("${result.streams.size} streams returned for test title.")
                                                    }
                                                }
                                                .onFailure { err ->
                                                    addonStatus[addon.transportUrl] = AddonStatus.Error(err.message ?: err.toString())
                                                }
                                        }
                                    },
                                    onConfigure = {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${addon.transportUrl}/configure"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }
                                    },
                                    onRemove = {
                                        addonStatus.remove(addon.transportUrl)
                                        mutate { current ->
                                            current.copy(
                                                installedAddons = current.installedAddons.filterNot { it.transportUrl == addon.transportUrl },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showAddAddonDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferAccent),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add by URL")
                        }
                    }

                    val installedUrls = remember(settings.installedAddons) {
                        settings.installedAddons.map { it.transportUrl }.toSet()
                    }
                    val notInstalled = remember(installedUrls) {
                        AddonPresets.filterNot { it.transportUrl in installedUrls }
                    }
                    // State for the "configure then install" flow used by
                    // config-required presets (MediaFusion, Comet, etc.).
                    var configurePreset by remember { mutableStateOf<AddonPreset?>(null) }

                    if (notInstalled.isNotEmpty()) {
                        SettingsSection(title = "Suggested addons") {
                            notInstalled.forEach { preset ->
                                PresetAddonRow(
                                    preset = preset,
                                    onInstall = { onError ->
                                        // Config-required addons go through the configure flow —
                                        // installing the bare URL would just hand back zero streams
                                        // until the user sets up a debrid token / providers.
                                        if (preset.configRequired) {
                                            configurePreset = preset
                                            onError("Configure required — opening setup.")
                                        } else {
                                            scope.launch {
                                                runCatching { fetchAndParseManifest(preset.transportUrl) }
                                                    .onSuccess { addon ->
                                                        repo.update { current ->
                                                            val withoutDup = current.installedAddons.filterNot { it.transportUrl == addon.transportUrl }
                                                            current.copy(installedAddons = withoutDup + addon)
                                                        }
                                                    }
                                                    .onFailure { err ->
                                                        onError(err.message ?: err.toString())
                                                    }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    configurePreset?.let { preset ->
                        ConfigurePresetDialog(
                            preset = preset,
                            onDismiss = { configurePreset = null },
                            onOpenConfigure = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${preset.transportUrl}/configure"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            },
                            onInstall = { rawUrl, onError ->
                                scope.launch {
                                    runCatching { fetchAndParseManifest(rawUrl) }
                                        .onSuccess { addon ->
                                            repo.update { current ->
                                                val withoutDup = current.installedAddons.filterNot { it.transportUrl == addon.transportUrl }
                                                current.copy(installedAddons = withoutDup + addon)
                                            }
                                            configurePreset = null
                                        }
                                        .onFailure { err ->
                                            onError(err.message ?: err.toString())
                                        }
                                }
                            },
                        )
                    }
                }

                SettingsTab.Sync -> {
                    TraktSyncSection()
                    AniListSyncSection()
                }

                SettingsTab.General -> {
                    SettingsSection(title = "TMDb (advanced)") {
                        Text(
                            text = "Cyfer ships with a default TMDb token. Add your own only if you've burned through the shared one's quota.",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SecretField(
                            label = "Read-only bearer token (optional)",
                            value = settings.tmdbReadToken,
                            onValueChange = { v -> mutate { it.copy(tmdbReadToken = v.trim()) } },
                            hint = "Leave blank to use the bundled default.",
                        )
                    }
                    SettingsSection(title = "About") {
                        Text(
                            text = "Cyfer Streaming · Android",
                            color = CyferWhite,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Version ${app.cyfer.streaming.android.BuildConfig.VERSION_NAME} (build ${app.cyfer.streaming.android.BuildConfig.VERSION_CODE})",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Built on Compose + libmpv-android + libtorrent4j.",
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Lazy update check — one GitHub releases fetch
                        // per app session; row only appears when a newer
                        // tag exists, tapping opens the release page.
                        val aboutCtx = androidx.compose.ui.platform.LocalContext.current
                        val updateInfo by produceState<app.cyfer.streaming.android.data.updates.UpdateChecker.UpdateInfo?>(initialValue = null) {
                            value = app.cyfer.streaming.android.data.updates.UpdateChecker
                                .checkForNewerRelease(app.cyfer.streaming.android.BuildConfig.VERSION_NAME)
                        }
                        updateInfo?.let { info ->
                            Surface(
                                onClick = {
                                    runCatching {
                                        aboutCtx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(info.releaseUrl),
                                            ),
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = CyferAccent.copy(alpha = 0.14f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Update available — v${info.versionName}",
                                            color = CyferAccent,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "Tap to open the release page and grab the new APK.",
                                            color = CyferTextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
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

    if (showAddAddonDialog) {
        AddAddonDialog(
            onDismiss = { showAddAddonDialog = false },
            onInstall = { url, onError ->
                scope.launch {
                    runCatching { fetchAndParseManifest(url) }
                        .onSuccess { addon ->
                            repo.update { current ->
                                val withoutDup = current.installedAddons.filterNot { it.transportUrl == addon.transportUrl }
                                current.copy(installedAddons = withoutDup + addon)
                            }
                            showAddAddonDialog = false
                        }
                        .onFailure { err -> onError(err.message ?: err.toString()) }
                }
            },
        )
    }
}

@Composable
private fun HardwareDecodingPicker(
    current: HardwareDecodingMode,
    onPick: (HardwareDecodingMode) -> Unit,
) {
    // Software decoding was removed by design — phone SoCs can't SW-decode
    // 4K HEVC at watchable speed, so offering it just produces complaints.
    // The OFF enum case survives only for old persisted settings; anyone
    // who had it selected sees Auto behaviour via the picker below.
    val options = listOf(
        Triple(HardwareDecodingMode.AUTO, "Auto", "Fastest path. Use this unless something breaks."),
        Triple(HardwareDecodingMode.COPY, "Copy", "Slower, but works around buggy chipsets."),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label, description) ->
            val selected = mode == current
            Surface(
                onClick = { onPick(mode) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) CyferCardSurfaceLight else CyferCardSurface,
                border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, CyferAccent) else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onPick(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = CyferAccent,
                            unselectedColor = CyferTextTertiary,
                        ),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            color = CyferWhite,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = description,
                            color = CyferTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title.uppercase(),
            color = CyferTextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            color = CyferDarkSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = CyferWhite,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyferBlack,
                checkedTrackColor = CyferAccent,
                uncheckedThumbColor = CyferTextTertiary,
                uncheckedTrackColor = CyferCardSurface,
                uncheckedBorderColor = CyferCardSurfaceLight,
            ),
        )
    }
}

/**
 * Single-row picker for an enum-backed setting. Compact dropdown-style
 * surface that expands into a column of choices on tap. Matches the
 * Hardware Decoding pattern but without the per-option descriptions
 * (the parent row's `description` covers that).
 */
@Composable
private fun <T : Enum<T>> EnumPickerRow(
    label: String,
    description: String?,
    options: List<Pair<T, String>>,
    current: T,
    onPick: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: current.name
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(12.dp),
            color = CyferCardSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, color = CyferWhite, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (!description.isNullOrBlank()) {
                        Text(description, color = CyferTextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(currentLabel, color = CyferAccent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (value, optLabel) ->
                    val selected = value == current
                    Surface(
                        onClick = { onPick(value); expanded = false },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) CyferCardSurfaceLight else CyferCardSurface,
                        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, CyferAccent) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onPick(value); expanded = false },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = CyferAccent,
                                    unselectedColor = CyferTextTertiary,
                                ),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(optLabel, color = CyferWhite, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Integer slider with discrete steps + label + description + live
 * value readout. Used for SDR target peak (100..1000 nits, 25 step).
 */
@Composable
private fun IntSliderRow(
    label: String,
    description: String?,
    range: IntRange,
    step: Int,
    current: Int,
    valueLabel: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = CyferWhite, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (!description.isNullOrBlank()) {
                    Text(description, color = CyferTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(valueLabel(current), color = CyferAccent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        val steps = ((range.last - range.first) / step) - 1
        Slider(
            value = current.toFloat(),
            onValueChange = { v ->
                val snapped = (v / step).toInt() * step
                onChange(snapped.coerceIn(range.first, range.last))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = CyferAccent,
                activeTrackColor = CyferAccent,
                inactiveTrackColor = CyferCardSurfaceLight,
            ),
        )
    }
}

@Composable
private fun ProviderRow(
    label: String,
    baseUrl: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) CyferWhite else CyferTextTertiary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = baseUrl,
                color = CyferTextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyferBlack,
                checkedTrackColor = CyferAccent,
                uncheckedThumbColor = CyferTextTertiary,
                uncheckedTrackColor = CyferCardSurface,
                uncheckedBorderColor = CyferCardSurfaceLight,
                disabledCheckedThumbColor = CyferTextTertiary,
                disabledCheckedTrackColor = CyferCardSurface,
                disabledUncheckedThumbColor = CyferTextTertiary,
                disabledUncheckedTrackColor = CyferCardSurface,
            ),
        )
    }
}

sealed interface AddonStatus {
    val message: String
    data class Working(override val message: String) : AddonStatus
    data class Ok(override val message: String) : AddonStatus
    data class Error(override val message: String) : AddonStatus
}

@Composable
private fun AddonRow(
    addon: InstalledAddon,
    status: AddonStatus?,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.name,
                    color = if (addon.enabled) CyferWhite else CyferTextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    addon.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    addon.transportUrl,
                ).joinToString("  ·  ")
                Text(
                    text = subtitle,
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = addon.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CyferBlack,
                    checkedTrackColor = CyferAccent,
                    uncheckedThumbColor = CyferTextTertiary,
                    uncheckedTrackColor = CyferCardSurface,
                    uncheckedBorderColor = CyferCardSurfaceLight,
                ),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = CyferTextSecondary)
            }
        }
        // Compact action chip row — Test / Refresh / Configure.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddonActionChip(label = "Test", onClick = onTest)
            AddonActionChip(label = "Refresh", onClick = onRefresh)
            AddonActionChip(label = "Configure", onClick = onConfigure, leading = {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = CyferTextSecondary, modifier = Modifier.size(12.dp))
            })
        }
        // Status feedback line — surfaces refresh/test/configure outcome.
        status?.let {
            val (color, prefix) = when (it) {
                is AddonStatus.Ok -> CyferAccent to "✓"
                is AddonStatus.Error -> CyferError to "!"
                is AddonStatus.Working -> CyferTextSecondary to "…"
            }
            Text(
                text = "$prefix  ${it.message}",
                color = color,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddonActionChip(
    label: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, CyferCardSurfaceLight),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            leading?.invoke()
            Text(
                text = label,
                color = CyferTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PresetAddonRow(
    preset: AddonPreset,
    onInstall: (onError: (String) -> Unit) -> Unit,
) {
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = preset.name,
                        color = CyferWhite,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CyferTagPill(
                        text = preset.category.uppercase(),
                        background = CyferCardSurfaceLight,
                        foreground = CyferTextSecondary,
                    )
                    if (preset.configRequired) {
                        CyferTagPill(
                            text = "NEEDS CONFIG",
                            background = CyferError.copy(alpha = 0.15f),
                            foreground = CyferError,
                        )
                    }
                }
                Text(
                    text = preset.tagline,
                    color = CyferTextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    installing = true
                    error = null
                    onInstall { msg ->
                        installing = false
                        error = msg
                    }
                },
                enabled = !installing,
                colors = ButtonDefaults.textButtonColors(contentColor = CyferAccent),
            ) {
                if (installing) {
                    CircularProgressIndicator(color = CyferAccent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Install")
                }
            }
        }
        error?.let {
            Text(
                text = "!  $it",
                color = CyferError,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurePresetDialog(
    preset: AddonPreset,
    onDismiss: () -> Unit,
    onOpenConfigure: () -> Unit,
    onInstall: (rawUrl: String, onError: (String) -> Unit) -> Unit,
) {
    var configuredUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        containerColor = CyferDarkSurface,
        titleContentColor = CyferWhite,
        textContentColor = CyferTextSecondary,
        title = { Text("Configure ${preset.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = preset.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = CyferTextSecondary,
                )

                // ── Step 1: open Configure ─────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "STEP 1  ·  CONFIGURE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyferTextTertiary,
                    )
                    Text(
                        text = "Opens ${preset.transportUrl}/configure in your browser. Set your debrid token, providers, and quality filters there, then copy the configured manifest URL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyferTextSecondary,
                    )
                    OutlinedButton(
                        onClick = onOpenConfigure,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferAccent),
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Configure Page")
                    }
                }

                // ── Step 2: paste the configured URL ───────────────────
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "STEP 2  ·  PASTE CONFIGURED URL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyferTextTertiary,
                    )
                    OutlinedTextField(
                        value = configuredUrl,
                        onValueChange = { configuredUrl = it; error = null },
                        placeholder = { Text("https://… or stremio://…", color = CyferTextTertiary) },
                        singleLine = false,
                        maxLines = 3,
                        enabled = !installing,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CyferWhite,
                            unfocusedTextColor = CyferWhite,
                            cursorColor = CyferAccent,
                            focusedBorderColor = CyferAccent,
                            unfocusedBorderColor = CyferCardSurfaceLight,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    error?.let {
                        Text(it, color = CyferError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !installing && configuredUrl.isNotBlank(),
                onClick = {
                    installing = true
                    error = null
                    onInstall(configuredUrl.trim()) { msg ->
                        installing = false
                        error = msg
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = CyferAccent),
            ) {
                if (installing) {
                    CircularProgressIndicator(color = CyferAccent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (installing) "Installing…" else "Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) {
                Text("Cancel", color = CyferTextSecondary)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAddonDialog(
    onDismiss: () -> Unit,
    onInstall: (url: String, onError: (String) -> Unit) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        containerColor = CyferDarkSurface,
        titleContentColor = CyferWhite,
        textContentColor = CyferTextSecondary,
        title = { Text("Add Stremio addon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Paste an addon URL — for example https://torrentio.strem.fun/manifest.json or just the base URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyferTextSecondary,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Manifest URL", color = CyferTextSecondary) },
                    singleLine = true,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CyferWhite,
                        unfocusedTextColor = CyferWhite,
                        cursorColor = CyferAccent,
                        focusedBorderColor = CyferAccent,
                        unfocusedBorderColor = CyferCardSurfaceLight,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                error?.let {
                    Text(it, color = CyferError, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !installing && url.isNotBlank(),
                onClick = {
                    installing = true
                    error = null
                    onInstall(url) { msg ->
                        installing = false
                        error = msg
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = CyferAccent),
            ) {
                if (installing) {
                    CircularProgressIndicator(color = CyferAccent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (installing) "Installing…" else "Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) {
                Text("Cancel", color = CyferTextSecondary)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = CyferTextSecondary) },
        placeholder = { Text(hint, color = CyferTextTertiary) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide" else "Show",
                    tint = CyferTextSecondary,
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = CyferWhite,
            unfocusedTextColor = CyferWhite,
            cursorColor = CyferAccent,
            focusedBorderColor = CyferAccent,
            unfocusedBorderColor = CyferCardSurfaceLight,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}
