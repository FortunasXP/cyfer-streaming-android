package app.cyfer.streaming.android.data.debrid

/**
 * Shared file-picking logic for both Real-Debrid and TorBox.
 *
 * Mirrors `choosePlayableFile` from `src/lib/real-debrid.ts` and
 * `src/lib/torbox.ts` — same scoring heuristics so a movie torrent
 * always returns the feature file and a TV pack picks the right episode.
 *
 * Each debrid-specific caller adapts its file list into [PlayableFile]
 * (preselected flag is RD-specific; TorBox passes false).
 */

private val VIDEO_EXTENSIONS = setOf(
    ".mkv", ".mp4", ".m4v", ".mov", ".avi", ".webm", ".ts", ".m2ts",
    ".mts", ".wmv", ".flv", ".mpg", ".mpeg", ".vob",
)

private val SAMPLE_RE = Regex(
    "(^|[ ._\\-\\[({])sample([ ._\\-\\])}]|$)|trailer|featurette|extras?",
    RegexOption.IGNORE_CASE,
)

private val DIACRITIC_RE = Regex("[\\u0300-\\u036f]")
private val STOP_TOKENS = setOf("episode", "season", "part", "chapter")

data class PlayableFile(
    val id: Int,
    val name: String,
    val sizeBytes: Long,
    /** Real-Debrid only: was this file already marked selected by the user. */
    val isPreselected: Boolean = false,
)

private fun extensionOf(value: String): String =
    Regex("(\\.[a-z0-9]{2,5})(?:$|[?#])", RegexOption.IGNORE_CASE).find(value.lowercase())?.groupValues?.getOrNull(1) ?: ""

private data class Score(val isVideo: Boolean, val isSample: Boolean, val length: Long)

private fun scoreOf(file: PlayableFile): Score {
    val ext = extensionOf(file.name)
    return Score(
        isVideo = VIDEO_EXTENSIONS.contains(ext),
        isSample = SAMPLE_RE.containsMatchIn(file.name),
        length = file.sizeBytes,
    )
}

private fun normaliseSearchText(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(DIACRITIC_RE, "")
        .replace("\\", "/")
        .lowercase()

private fun escapeRegExp(value: String): String =
    Regex("[.*+?^${'$'}{}()|\\[\\]\\\\]").replace(value) { "\\${it.value}" }

private fun compileFileMatcher(pattern: String?): ((String) -> Boolean)? {
    val raw = pattern?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val regexLiteral = Regex("^/(.+)/([dgimsuvy]*)$").find(raw)
    if (regexLiteral != null) {
        val opts = mutableSetOf<RegexOption>()
        if (regexLiteral.groupValues[2].contains('i')) opts += RegexOption.IGNORE_CASE
        return runCatching { Regex(regexLiteral.groupValues[1], opts).let { r -> { v: String -> r.containsMatchIn(v) } } }.getOrNull()
    }
    val lower = raw.lowercase()
    return { v -> v.lowercase().contains(lower) }
}

private fun episodeFileMatcher(req: DebridResolveRequest): ((String) -> Boolean)? {
    val season = req.season?.takeIf { it > 0 } ?: return null
    val episode = req.episode?.takeIf { it > 0 } ?: return null
    val s = "%02d".format(season)
    val e = "%02d".format(episode)
    val looseS = season.toString()
    val looseE = episode.toString()
    val patterns = mutableListOf(
        Regex("(^|[^a-z0-9])s0*${escapeRegExp(looseS)}[ ._\\-]*e0*${escapeRegExp(looseE)}([^a-z0-9]|$)", RegexOption.IGNORE_CASE),
        Regex("(^|[^0-9])0*${escapeRegExp(looseS)}[xX]0*${escapeRegExp(looseE)}([^0-9]|$)", RegexOption.IGNORE_CASE),
        Regex("season[ ._\\-]*0*${escapeRegExp(looseS)}[ ._\\-]*(episode|ep)[ ._\\-]*0*${escapeRegExp(looseE)}([^0-9]|$)", RegexOption.IGNORE_CASE),
        Regex("(^|[^a-z0-9])${escapeRegExp(s)}[ ._\\-]*${escapeRegExp(e)}([^a-z0-9]|$)", RegexOption.IGNORE_CASE),
    )
    if (season == 1) {
        patterns += Regex("(^|[\\/\\s._\\-\\[({])0*${escapeRegExp(looseE)}([\\s._\\-\\])}]|$)", RegexOption.IGNORE_CASE)
    }
    return { v -> patterns.any { it.containsMatchIn(v) } }
}

private fun episodeTitleMatcher(req: DebridResolveRequest): ((String) -> Boolean)? {
    val title = req.episodeTitle?.let(::normaliseSearchText).orEmpty()
    if (title.isBlank()) return null
    val tokens = title.split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in STOP_TOKENS }
    if (tokens.isEmpty()) return null
    return { value ->
        val haystack = normaliseSearchText(value)
        val hits = tokens.count { haystack.contains(it) }
        hits >= minOf(2, tokens.size)
    }
}

private fun bestVideoMatching(files: List<PlayableFile>, predicate: (String) -> Boolean): PlayableFile? =
    files.filter { predicate(it.name) }
        .map { it to scoreOf(it) }
        .filter { it.second.isVideo }
        .sortedWith(compareBy({ if (it.second.isSample) 1 else 0 }, { -it.second.length }))
        .firstOrNull()?.first

/**
 * Pick the most likely playable file from a torrent's file list. Returns
 * null only if [files] is empty.
 */
fun choosePlayableFile(files: List<PlayableFile>, req: DebridResolveRequest): PlayableFile? {
    if (files.isEmpty()) return null

    req.fileIdx?.let { idx -> files.getOrNull(idx)?.let { return it } }

    for (hint in listOf(req.fileMustInclude, req.filename)) {
        val matcher = compileFileMatcher(hint) ?: continue
        val matched = bestVideoMatching(files) { matcher(it) }
        if (matched != null) return matched
    }

    episodeFileMatcher(req)?.let { matcher ->
        bestVideoMatching(files) { matcher(it) }?.let { return it }
    }
    episodeTitleMatcher(req)?.let { matcher ->
        bestVideoMatching(files) { matcher(it) }?.let { return it }
    }

    files.filter { it.isPreselected }
        .map { it to scoreOf(it) }
        .filter { it.second.isVideo }
        .maxByOrNull { it.second.length }
        ?.first?.let { return it }

    files.map { it to scoreOf(it) }
        .filter { it.second.isVideo }
        .sortedWith(compareBy({ if (it.second.isSample) 1 else 0 }, { -it.second.length }))
        .firstOrNull()
        ?.first?.let { return it }

    return files.maxByOrNull { it.sizeBytes }
}
