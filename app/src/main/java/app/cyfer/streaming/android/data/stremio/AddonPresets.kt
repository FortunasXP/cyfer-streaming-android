package app.cyfer.streaming.android.data.stremio

/**
 * Curated list of well-known Stremio **stream** addons. We deliberately
 * exclude metadata-only (Cinemeta) and subtitle-only (OpenSubtitles)
 * addons here because they return zero streams in our source picker and
 * just look broken to the user. Add those back when we wire a real
 * metadata / subtitle pipeline.
 *
 * Order matters — the top of the list is the recommended starter set.
 */
data class AddonPreset(
    val name: String,
    val tagline: String,
    val transportUrl: String,
    /** Marketing-style category — used as a small badge on the row. */
    val category: String,
    /** True when the addon needs the user to walk through /configure first
     *  (debrid token, account, etc.). We surface it as a label. */
    val configRequired: Boolean = false,
)

val AddonPresets: List<AddonPreset> = listOf(
    AddonPreset(
        name = "Torrentio",
        tagline = "Public torrent index — works without setup. Open /configure to add a debrid token for cached streams.",
        // The /providers= path is the most useful default — drops cams, scrs
        // and 480p from results so the picker shows quality torrents only.
        transportUrl = "https://torrentio.strem.fun/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex",
        category = "Torrents",
    ),
    AddonPreset(
        name = "MediaFusion",
        tagline = "Multi-source aggregator (torrents + direct + debrid). Visit /configure for best results.",
        transportUrl = "https://mediafusion.elfhosted.com",
        category = "Torrents",
        configRequired = true,
    ),
    AddonPreset(
        name = "Comet",
        tagline = "Debrid-cached scraper alternative to Torrentio. Requires a debrid token via /configure.",
        transportUrl = "https://comet.elfhosted.com",
        category = "Debrid",
        configRequired = true,
    ),
)
