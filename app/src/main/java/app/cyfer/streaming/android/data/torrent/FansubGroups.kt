package app.cyfer.streaming.android.data.torrent

/**
 * Anime release-group detection — mirrors `src/lib/fansub-groups.ts`.
 * Used both for badge text and for ranking matches against the user's
 * `animeFansubGroupPreferences` list.
 */

val DEFAULT_ANIME_FANSUB_GROUP_PRESETS = listOf(
    "SubsPlease",
    "Erai-raws",
    "Judas",
    "ASW",
    "Anime Time",
    "ToonsHub",
    "EMBER",
    "Yameii",
    "NanDesuKa",
    "LostYears",
    "VCB-Studio",
)

private val KNOWN_GROUP_ALIASES: Map<String, List<String>> = mapOf(
    "SubsPlease" to listOf("subsplease", "subs please"),
    "Erai-raws" to listOf("erai raws", "erai-raws", "erairaws"),
    "Judas" to listOf("judas"),
    "ASW" to listOf("asw"),
    "Anime Time" to listOf("anime time", "anime-time", "animetime"),
    "ToonsHub" to listOf("toonshub", "toons hub", "toons-hub"),
    "EMBER" to listOf("ember"),
    "Yameii" to listOf("yameii"),
    "NanDesuKa" to listOf("nandesuka", "nan desu ka", "nan-desu-ka"),
    "LostYears" to listOf("lostyears", "lost years", "lost-years"),
    "VCB-Studio" to listOf("vcb studio", "vcb-studio", "vcbstudio"),
)

private val DIACRITIC = Regex("[\\u0300-\\u036f]")
private val NON_WORD = Regex("[^a-z0-9]+")

private fun groupKey(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(DIACRITIC, "")
        .lowercase()
        .replace(NON_WORD, " ")
        .trim()

private fun cleanGroupName(value: String): String = value
    .replace(Regex("^\\s*[\\[({]+"), "")
    .replace(Regex("[\\])}]+\\s*$"), "")
    .replace(Regex("[_]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

fun normaliseAnimeFansubGroupName(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val cleaned = cleanGroupName(value)
    if (cleaned.isEmpty()) return null
    val key = groupKey(cleaned)
    for ((canonical, aliases) in KNOWN_GROUP_ALIASES) {
        if (groupKey(canonical) == key || aliases.any { groupKey(it) == key }) return canonical
    }
    return if (cleaned.length <= 40) cleaned else null
}

fun normaliseAnimeFansubGroupPreferences(value: List<String>?): List<String> {
    if (value.isNullOrEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val groups = mutableListOf<String>()
    for (item in value) {
        val group = normaliseAnimeFansubGroupName(item) ?: continue
        val key = groupKey(group)
        if (key in seen) continue
        seen += key
        groups += group
        if (groups.size >= 12) break
    }
    return groups
}

private val BRACKET_PREFIX = Regex("^\\s*\\[([^\\]]{2,42})\\]")
private val TECH_TOKEN_GUARD = Regex(
    "\\b(4k|2160p|1080p|720p|480p|hevc|x26[45]|av1|web|blu-?ray|dual|multi)\\b",
    RegexOption.IGNORE_CASE,
)

fun detectAnimeReleaseGroup(text: String): String? {
    BRACKET_PREFIX.find(text)?.groupValues?.getOrNull(1)?.let { bracket ->
        val group = normaliseAnimeFansubGroupName(bracket)
        if (group != null && !TECH_TOKEN_GUARD.containsMatchIn(group)) return group
    }
    val haystack = " ${groupKey(text)} "
    for ((canonical, aliases) in KNOWN_GROUP_ALIASES) {
        val keys = (listOf(canonical) + aliases).map { groupKey(it) }.filter { it.isNotEmpty() }
        if (keys.any { " $it " in haystack }) return canonical
    }
    return null
}
