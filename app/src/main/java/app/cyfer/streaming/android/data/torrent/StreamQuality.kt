package app.cyfer.streaming.android.data.torrent

/**
 * Quality / language parsing helpers — mirrors `src/lib/stremio.ts`
 * `parseStreamQuality` and friends. We keep the same set of fields the
 * desktop UI displays so the Android source picker can render the same
 * badges (4K / HDR / DV / IMAX / ATMOS / 10bit / codec / size).
 */

enum class StreamResolution { K4, P1080, P720, P480;
    val label: String get() = when (this) {
        K4 -> "4K"; P1080 -> "1080p"; P720 -> "720p"; P480 -> "480p"
    }
}

data class StreamQuality(
    val resolution: StreamResolution? = null,
    val hdr: Boolean = false,
    val dolbyVision: Boolean = false,
    val imax: Boolean = false,
    val atmos: Boolean = false,
    val tenBit: Boolean = false,
    val codec: String? = null,
    val size: String? = null,
    val languages: List<String> = emptyList(),
)

private const val NEXT_TECH_TOKEN =
    "hdr|hevc|h\\.?265|x\\.?265|h\\.?264|x\\.?264|avc|av1|web|bluray|blu-?ray|bdrip|brrip|remux|dv|dovi|10bit|8bit"

private fun hasQualityToken(text: String, pattern: String): Boolean {
    val regex = Regex(
        "(?:^|[\\s._()\\[\\]{}-])(?:$pattern)(?=$|[\\s._()\\[\\]{}-]|(?=$NEXT_TECH_TOKEN))",
        RegexOption.IGNORE_CASE,
    )
    return regex.containsMatchIn(text)
}

fun parseStreamResolution(text: String): StreamResolution? = when {
    hasQualityToken(text, "2160p?|4k|uhd|ultrahd|ultra\\s*hd") -> StreamResolution.K4
    hasQualityToken(text, "1080p|fhd|full\\s*hd") -> StreamResolution.P1080
    hasQualityToken(text, "720p") -> StreamResolution.P720
    hasQualityToken(text, "480p") -> StreamResolution.P480
    else -> null
}

/** Minimal language detection — enough for badge text. Compact subset of the desktop list. */
private val LANGUAGE_HINTS: List<Pair<String, List<String>>> = listOf(
    "EN" to listOf("eng", "english"),
    "JA" to listOf("jpn", "japanese"),
    "ES" to listOf("spa", "esp", "spanish", "castilian"),
    "FR" to listOf("fre", "fra", "french"),
    "DE" to listOf("ger", "deu", "german"),
    "IT" to listOf("ita", "italian"),
    "PT" to listOf("por", "portuguese"),
    "PT-BR" to listOf("pob", "pt-br", "brazilian portuguese"),
    "RU" to listOf("rus", "russian"),
    "ZH" to listOf("chi", "zho", "chinese", "mandarin", "cantonese"),
    "KO" to listOf("kor", "korean"),
    "AR" to listOf("ara", "arabic"),
    "HI" to listOf("hin", "hindi"),
    "TR" to listOf("tur", "turkish"),
    "PL" to listOf("pol", "polish"),
    "NL" to listOf("dut", "nld", "dutch"),
)

fun parseStreamLanguages(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val matches = mutableListOf<Pair<String, Int>>()
    for ((tag, patterns) in LANGUAGE_HINTS) {
        val earliest = patterns.mapNotNull { pattern ->
            val r = Regex(
                "(?:^|[\\s._()\\[\\]{}+|,:;/-])(${Regex.escape(pattern).replace("\\ ", "\\s+")})(?=$|[\\s._()\\[\\]{}+|,:;/-])",
                RegexOption.IGNORE_CASE,
            )
            r.find(text)?.range?.first
        }.minOrNull()
        if (earliest != null) matches += tag to earliest
    }
    Regex("\\b(?:MULTI|MULTISUB|MULTI[-\\s.]?LANG|MULTI[-\\s.]?AUDIO)\\b", RegexOption.IGNORE_CASE)
        .find(text)?.range?.first?.let { matches += "MULTI" to it }
    Regex("\\b(?:DUAL[-\\s.]?AUDIO|DUALAUDIO)\\b", RegexOption.IGNORE_CASE)
        .find(text)?.range?.first?.let { matches += "DUAL" to it }
    return matches.sortedBy { it.second }.map { it.first }.distinct()
}

fun parseStreamQuality(text: String): StreamQuality {
    val upper = text.uppercase()
    return StreamQuality(
        resolution = parseStreamResolution(text),
        hdr = Regex("HDR10(\\+|P(lus)?)", RegexOption.IGNORE_CASE).containsMatchIn(text)
            || Regex("HDR10", RegexOption.IGNORE_CASE).containsMatchIn(text)
            || Regex("\\bHDR\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            || Regex("\\bHLG\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            || Regex("\\bPQ\\b", RegexOption.IGNORE_CASE).containsMatchIn(text),
        dolbyVision = Regex("\\bDV\\b").containsMatchIn(upper)
            || Regex("DOLBY.?VISION", RegexOption.IGNORE_CASE).containsMatchIn(text)
            || Regex("DoVi", RegexOption.IGNORE_CASE).containsMatchIn(text),
        imax = Regex("\\bIMAX\\b", RegexOption.IGNORE_CASE).containsMatchIn(text),
        atmos = Regex("\\bATMOS\\b", RegexOption.IGNORE_CASE).containsMatchIn(text),
        tenBit = Regex("10[.\\-]?bit", RegexOption.IGNORE_CASE).containsMatchIn(text)
            || Regex("\\bhi10p\\b", RegexOption.IGNORE_CASE).containsMatchIn(text),
        codec = when {
            Regex("\\bHEVC\\b|x\\.?265|H\\.?265", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "HEVC"
            Regex("\\bAV1\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "AV1"
            Regex("x\\.?264|H\\.?264|AVC", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "H.264"
            else -> null
        },
        size = Regex("(\\d+(?:\\.\\d+)?\\s*(?:GiB|MiB|GB|MB|KB))", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1),
        languages = parseStreamLanguages(text),
    )
}
