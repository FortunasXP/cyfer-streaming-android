package app.cyfer.streaming.android.player

/**
 * The resolved decision for "what should libplacebo emit for THIS source
 * on THIS display" — the content-format × display-capability matrix.
 *
 * Background for future readers: mpv/libplacebo always *renders* the
 * final pixels on the GPU. There is no tunneled Dolby Vision bitstream
 * passthrough on this path (that's a MediaCodec-direct-to-surface
 * feature used by DRM apps). So "playing Dolby Vision" here means:
 * libdovi parses the RPU, libplacebo applies the reshape, and the
 * result is emitted as PQ — which any HDR10-capable display reads.
 * A DV-branded panel gets exactly the same (already-reshaped) pixels.
 */
data class HdrOutputPlan(
    val targetPrim: String,
    val targetTrc: String,
    /** mpv target-peak value — a nit count or "auto". */
    val targetPeak: String,
    val targetContrast: String,
    /** Whether to ask the compositor for an HDR colorspace. */
    val colorspaceHint: Boolean,
    /** Human-readable one-liner for the diagnostic overlay, e.g.
     *  "DV P8.1 → PQ out · display HDR10/HLG". */
    val description: String,
)

/**
 * Decide the output target from source metadata + display capabilities
 * + user config. Pure function — trivially testable, no mpv calls.
 *
 * Priority order:
 *  1. Forced HDR override — ONLY honoured where it can work: the OS
 *     must be claiming SDR (on an honest HDR display the auto path
 *     already does the right thing, with the real panel peak) AND the
 *     EGL probe must have found an HDR-capable swapchain (no PQ/HLG
 *     extension = physically impossible — forcing would just dim the
 *     picture into an sRGB swapchain).
 *  2. SDR-only display → tone-map down (BT.2390 by default)
 *  3. HDR display:
 *     a. SDR source → native SDR out. target-colorspace-hint-mode=
 *        source-dynamic makes libplacebo negotiate the swapchain from
 *        the SOURCE space per file, so SDR content never gets wrapped
 *        in a PQ container (which would pin its reference white ~203
 *        nits and look dim next to every other app).
 *     b. HLG source on an HLG-capable display → emit HLG natively
 *        (skips a pointless HLG→PQ conversion; broadcast content keeps
 *        its system-gamma look)
 *     c. everything else (PQ/HDR10/HDR10+/DV-reshaped) → emit PQ
 *     d. target-peak = the panel's reported max luminance when the OS
 *        gives us one — better than "auto" because libplacebo can
 *        pre-shape highlights for the real panel instead of guessing
 */
fun planHdrOutput(
    source: HdrVideoMetadata,
    display: HdrDisplayCapabilities,
    cfg: MpvPlayer.HdrPipelineConfig,
    /** EglHdrProbe.hdrOutputPossible — whether the GL stack offers a
     *  10-bit/FP16 config + a BT.2020 PQ/HLG colorspace extension. */
    eglHdrCapable: Boolean = true,
): HdrOutputPlan {
    // Be honest about what the DV path is actually doing: "reshape" only
    // when frames carry RPUs (colormatrix=dolbyvision) AND the decode
    // path lets libplacebo apply them — copy/SW; zero-copy frames are
    // driver-converted to RGB before the shader. Otherwise we're
    // rendering the base layer — fine for P8 (BL = real HDR10/HLG),
    // visibly wrong for P5 (BL = IPTPQc2).
    val dvNote = when {
        source.dolbyVisionProfile == null -> null
        source.dolbyVisionReshapeActive -> "reshape"
        source.dolbyVisionP5BaseLayer -> "BL only — wrong colours!"
        else -> "HDR10 BL"
    }
    val sourceLabel = source.detailedLabel + (dvNote?.let { " ($it)" } ?: "")
    val displayShort = display.shortLabel

    // 1 ── user override: emit HDR despite the OS claiming SDR. Gated
    // on the EGL probe — if the GL stack can't signal HDR the toggle
    // cannot work, and on an honest HDR display the auto path below is
    // strictly better (it targets the panel's real peak).
    if (cfg.forceHdrOutput && !display.hdrCapable && eglHdrCapable) {
        val peak = cfg.forcedHdrPeakNits.coerceIn(200, 2000)
        return HdrOutputPlan(
            targetPrim = "bt.2020",
            targetTrc = "pq",
            targetPeak = peak.toString(),
            targetContrast = "auto",
            colorspaceHint = true,
            description = "$sourceLabel → PQ out (forced, $peak nit)",
        )
    }

    // 2 ── SDR-only panel: tone-map down
    if (!display.hdrCapable) {
        val peak = cfg.sdrTargetPeakNits.coerceIn(100, 1500)
        val how = if (source.active) "tone-map SDR (${cfg.toneMapping}, $peak nit)" else "SDR out"
        return HdrOutputPlan(
            targetPrim = "bt.709",
            targetTrc = "gamma2.2",
            targetPeak = peak.toString(),
            targetContrast = "1000",
            colorspaceHint = false,
            description = "$sourceLabel → $how",
        )
    }

    // 3 ── HDR-capable panel

    // SDR source: stay native. The source-dynamic colorspace hint
    // negotiates an SDR swapchain for SDR files all by itself; setting
    // explicit PQ targets here would only mislead the diagnostics (and
    // mpv ignores them once the hint succeeds anyway). Targets stay
    // "auto" so mpv renders plain SDR.
    if (!source.active) {
        return HdrOutputPlan(
            targetPrim = "auto",
            targetTrc = "auto",
            targetPeak = "auto",
            targetContrast = "auto",
            colorspaceHint = true,
            description = "$sourceLabel → SDR out (native) · display $displayShort",
        )
    }

    val sourceIsHlg = (source.transfer ?: "").lowercase().let {
        it.contains("hlg") || it.contains("arib-std-b67")
    }
    val displayHasHlg = HdrDisplayFormat.Hlg in display.formats

    // Panel max luminance from Display.HdrCapabilities — when the OS
    // reports a real number, hand it to libplacebo so highlight rolloff
    // is shaped for the actual panel rather than a guess.
    val panelPeak = display.desiredMaxLuminance
        ?.takeIf { it.isFinite() && it > 50f }
        ?.toInt()
    val peakValue = panelPeak?.toString() ?: "auto"
    val peakLabel = panelPeak?.let { "$it nit" } ?: "auto peak"

    return if (sourceIsHlg && displayHasHlg) {
        HdrOutputPlan(
            targetPrim = "bt.2020",
            targetTrc = "hlg",
            targetPeak = peakValue,
            targetContrast = "auto",
            colorspaceHint = true,
            description = "$sourceLabel → HLG out (native, $peakLabel) · display $displayShort",
        )
    } else {
        HdrOutputPlan(
            targetPrim = "bt.2020",
            targetTrc = "pq",
            targetPeak = peakValue,
            targetContrast = "auto",
            colorspaceHint = true,
            description = "$sourceLabel → PQ out ($peakLabel) · display $displayShort",
        )
    }
}
