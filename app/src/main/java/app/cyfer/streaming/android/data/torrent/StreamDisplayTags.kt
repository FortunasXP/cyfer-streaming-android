package app.cyfer.streaming.android.data.torrent

/**
 * Tag parsing for stream filenames. Each [TechTag] maps to either a
 * bundled brand logo (`assetPath`) or a text-only label.
 *
 * The hero (Details) and the source picker apply different aggregation
 * rules on top of the per-stream tags returned by [streamDisplayTags]:
 *
 *  - Hero ([aggregateTechTags]) — minimal: only **DV / Atmos / IMAX**
 *    survive. Everything else (HDR10, HDR10+, HLG, generic HDR, Dolby
 *    Digital family, DTS family, FLAC, …) is dropped to keep the hero
 *    badge row clean.
 *  - Source row ([streamDisplayTagsForRow]) — richer, but still
 *    deduplicated: HDR text/logos are suppressed when DV or IMAX is
 *    present, and only the **single highest-quality audio tag** is
 *    shown per row.
 */

enum class TechTag(val label: String, val assetPath: String?) {
    // ── HDR / video ──────────────────────────────────────────────
    DolbyVision("Dolby Vision", "logos/dolby-vision-vertical.png"),
    HDR10Plus("HDR10+", "logos/hdr10-plus.svg"),
    HDR10("HDR10", "logos/hdr10.svg"),
    HLG("HLG", null),
    HDR("HDR", null),
    IMAXEnhanced("IMAX Enhanced", "logos/imax-enhanced.svg"),

    // ── Audio ────────────────────────────────────────────────────
    DolbyAtmos("Atmos", "logos/dolby-atmos-vertical.png"),
    DolbyTrueHD("TrueHD", "logos/dolby-truehd.svg"),
    DolbyDigitalPlus("DD+", "logos/dolby-digital-plus.svg"),
    DolbyDigital("Dolby Digital", "logos/dolby-digital.svg"),
    DTSX("DTS:X", "logos/dts-x.svg"),
    DTSHDMA("DTS-HD MA", "logos/dts-hd-master-audio.svg"),
    DTSHD("DTS-HD", "logos/dts-hd-master-audio.svg"),
    DTS("DTS", "logos/dts.svg"),
    FLAC("FLAC", null),
    Opus("Opus", null),
    PCM("PCM", null),
}

private fun streamText(stream: ResolvedStream): String =
    listOf(stream.title, stream.addonName)
        .filter { it.isNotBlank() }
        .joinToString(" ")

private fun compact(value: String): String =
    value.uppercase().replace(Regex("[._\\-]+"), " ")

/** HDR tags inferred from release name + structured quality flags. */
fun sourceHdrTags(text: String, stream: ResolvedStream): List<TechTag> {
    val tags = mutableListOf<TechTag>()
    val hasDolbyVision = stream.quality.dolbyVision ||
        Regex("\\bDOLBY\\s*VISION\\b|\\bDOVI\\b|\\bDV\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    if (hasDolbyVision) tags += TechTag.DolbyVision

    when {
        Regex("\\bHDR10\\+", RegexOption.IGNORE_CASE).containsMatchIn(text) -> tags += TechTag.HDR10Plus
        Regex("\\bHDR10\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> tags += TechTag.HDR10
        Regex("\\bHLG\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> tags += TechTag.HLG
        !hasDolbyVision && (stream.quality.hdr || Regex("\\bHDR\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) ->
            tags += TechTag.HDR
    }

    if (stream.quality.imax || Regex("\\bIMAX\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        tags += TechTag.IMAXEnhanced
    }
    return tags
}

/** Audio tags inferred from release name. */
fun sourceAudioTags(text: String): List<TechTag> {
    val c = compact(text)
    val tags = mutableListOf<TechTag>()

    if (Regex("\\bTRUEHD\\b").containsMatchIn(c)) tags += TechTag.DolbyTrueHD
    if (Regex("\\bATMOS\\b").containsMatchIn(c)) tags += TechTag.DolbyAtmos

    when {
        Regex("\\bDTS\\s*X\\b").containsMatchIn(c) -> tags += TechTag.DTSX
        Regex("\\bDTS\\s*HD\\s*MA\\b|\\bDTSHD\\s*MA\\b").containsMatchIn(c) -> tags += TechTag.DTSHDMA
        Regex("\\bDTS\\s*HD\\b|\\bDTSHD\\b").containsMatchIn(c) -> tags += TechTag.DTSHD
        Regex("\\bDTS\\b").containsMatchIn(c) -> tags += TechTag.DTS
    }

    when {
        Regex("\\bE\\s*AC\\s*3\\b|\\bEAC3\\b|\\bDDP\\b|\\bDD\\+\\b|DOLBY\\s*DIGITAL\\s*PLUS").containsMatchIn(c) ->
            tags += TechTag.DolbyDigitalPlus
        Regex("\\bAC\\s*3\\b|\\bAC3\\b|DOLBY\\s*DIGITAL").containsMatchIn(c) ->
            tags += TechTag.DolbyDigital
    }

    if (Regex("\\bFLAC\\b").containsMatchIn(c)) tags += TechTag.FLAC
    if (Regex("\\bOPUS\\b").containsMatchIn(c)) tags += TechTag.Opus
    if (Regex("\\bPCM\\b|\\bLPCM\\b").containsMatchIn(c)) tags += TechTag.PCM

    return tags
}

/** Per-stream display tags — full union of HDR + audio inferences. */
fun streamDisplayTags(stream: ResolvedStream): List<TechTag> {
    val text = streamText(stream)
    val out = mutableListOf<TechTag>()
    for (tag in sourceHdrTags(text, stream)) if (tag !in out) out += tag
    for (tag in sourceAudioTags(text)) if (tag !in out) out += tag
    return out
}

/**
 * Highest-quality-first audio priority. Used by [streamDisplayTagsForRow]
 * to surface only the single best audio badge per source row.
 */
private val AUDIO_PRIORITY = listOf(
    TechTag.DolbyAtmos,
    TechTag.DTSX,
    TechTag.DolbyTrueHD,
    TechTag.DTSHDMA,
    TechTag.DTSHD,
    TechTag.DTS,
    TechTag.DolbyDigitalPlus,
    TechTag.DolbyDigital,
    TechTag.FLAC,
    TechTag.Opus,
    TechTag.PCM,
)

/**
 * Display tags for one row of the source picker, applying the dedup
 * rules:
 *  - HDR (HDR10+/HDR10/HLG/HDR) is suppressed when DV or IMAX is present
 *  - only the single highest-quality audio badge is kept
 *
 * Order: video logos (DV → IMAX → HDR family) then the best audio.
 */
fun streamDisplayTagsForRow(stream: ResolvedStream): List<TechTag> {
    val raw = streamDisplayTags(stream)
    val hasPremiumVideo = TechTag.DolbyVision in raw || TechTag.IMAXEnhanced in raw
    val out = mutableListOf<TechTag>()

    if (TechTag.DolbyVision in raw) out += TechTag.DolbyVision
    if (TechTag.IMAXEnhanced in raw) out += TechTag.IMAXEnhanced
    if (!hasPremiumVideo) {
        when {
            TechTag.HDR10Plus in raw -> out += TechTag.HDR10Plus
            TechTag.HDR10 in raw -> out += TechTag.HDR10
            TechTag.HLG in raw -> out += TechTag.HLG
            TechTag.HDR in raw -> out += TechTag.HDR
        }
    }

    AUDIO_PRIORITY.firstOrNull { it in raw }?.let { out += it }
    return out
}

/**
 * Extract a "5.1" / "7.1" / "2.0" channel-layout tag from a release name.
 */
fun extractChannelLayout(text: String): String? {
    val c = compact(text)
    Regex("(?:^|\\s)([257])\\s*\\.\\s*([01])(?:\\s|$)").find(c)?.let {
        return "${it.groupValues[1]}.${it.groupValues[2]}"
    }
    if (Regex("\\b8CH\\b|\\b8\\s*CHANNEL").containsMatchIn(c)) return "7.1"
    if (Regex("\\b6CH\\b|\\b6\\s*CHANNEL").containsMatchIn(c)) return "5.1"
    if (Regex("\\b2CH\\b|\\b2\\s*CHANNEL").containsMatchIn(c)) return "2.0"
    return null
}

private val TECH_TOKEN_RE = Regex(
    "^(2160p|1080p|720p|480p|4k|uhd|hdr|hdr10\\+?|hlg|dv|dovi|dolby|vision|imax|bluray|bdrip|brrip|web|web-dl|webdl|webrip|hdtv|remux|repack|proper|extended|x264|x265|h264|h265|hevc|avc|av1|10bit|8bit|truehd|atmos|dts|dts-hd|eac3|ddp|dd\\+|ac3|aac|flac|opus|pcm|multi)$",
    RegexOption.IGNORE_CASE,
)
private val TECH_INSIDE_RE = Regex(
    "^(2160p|1080p|720p|480p|4k|uhd|hdr10?\\+?|hlg|dv|dovi|dolby\\s*vision|imax|bluray|blu-ray|bdrip|brrip|web|web-dl|webdl|webrip|hdtv|remux|repack|proper|extended|x\\.?264|x\\.?265|h\\.?264|h\\.?265|hevc|avc|av1|10bit|8bit|truehd|atmos|dts|dts-hd|eac3|ddp|dd\\+|ac3|aac|flac|opus|pcm|multi|yts[\\s.]?mx|rarbg|NTb|FLUX|nTb|\\d+\\s*\\.\\s*\\d+|5\\s*1|7\\s*1)$",
    RegexOption.IGNORE_CASE,
)
private val YEAR_RE = Regex("^(19|20)\\d{2}$")

/**
 * Trim a release name down to just the movie/show title — drops
 * resolution / codec / HDR / audio / release-group tokens. Direct port
 * of `cleanReleaseTitle` in `src/components/title-details-screen.tsx`.
 */
fun cleanReleaseTitle(value: String): String {
    if (value.isBlank()) return value
    var stripped = value

    stripped = stripped.replace(Regex("\\[[^\\]]*\\]"), "")

    stripped = Regex("\\(([^)]*)\\)").replace(stripped) { match ->
        val inner = match.groupValues[1].trim()
        if (inner.isEmpty()) ""
        else if (TECH_INSIDE_RE.containsMatchIn(inner)) ""
        else if (YEAR_RE.containsMatchIn(inner)) ""
        else {
            val firstWord = inner.split(Regex("\\s+")).firstOrNull().orEmpty()
            if (TECH_INSIDE_RE.containsMatchIn(firstWord) || YEAR_RE.containsMatchIn(firstWord)) ""
            else match.value
        }
    }

    stripped = Regex("\\(([^)]+)$").replace(stripped) { match ->
        val inner = match.groupValues[1].trim()
        if (TECH_INSIDE_RE.containsMatchIn(inner) || YEAR_RE.containsMatchIn(inner)) "" else match.value
    }

    stripped = stripped
        .replace(Regex("\\.[a-z0-9]{2,4}$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("\\s*-\\s*[A-Z0-9]+$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    val tokens = stripped.split(" ").filter { it.isNotBlank() }
    val markerIndex = tokens.indexOfFirst { token ->
        val bare = token.replace(Regex("^[(\\[]+|[)\\]]+$"), "")
        TECH_TOKEN_RE.containsMatchIn(bare)
    }
    val titleTokens = if (markerIndex > 0) tokens.take(markerIndex) else tokens
    val cleaned = titleTokens.joinToString(" ").trim()
    return cleaned.ifEmpty { stripped.ifEmpty { value } }
}

/**
 * Aggregate badges for the Details hero tech row — strictly DV, IMAX,
 * Atmos. Everything else gets dropped to keep the hero minimal.
 */
data class AggregatedTechTags(
    val videoLogos: List<TechTag>,   // DolbyVision and/or IMAXEnhanced
    val audioLogos: List<TechTag>,   // DolbyAtmos only (or empty)
)

fun aggregateTechTags(streams: List<ResolvedStream>): AggregatedTechTags {
    if (streams.isEmpty()) return AggregatedTechTags(emptyList(), emptyList())
    val seen = LinkedHashSet<TechTag>()
    for (stream in streams) {
        for (tag in streamDisplayTags(stream)) seen += tag
    }
    val videoLogos = listOfNotNull(
        TechTag.IMAXEnhanced.takeIf { it in seen },
        TechTag.DolbyVision.takeIf { it in seen },
    )
    val audioLogos = listOfNotNull(TechTag.DolbyAtmos.takeIf { it in seen })
    return AggregatedTechTags(videoLogos = videoLogos, audioLogos = audioLogos)
}
