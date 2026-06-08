package app.cyfer.streaming.android.data.stremio

import kotlinx.serialization.Serializable

/**
 * Cyfer's flattened view of a Stremio addon manifest — only the fields we
 * actually use at runtime, so the AppSettings JSON blob doesn't carry the
 * full upstream manifest with every install.
 *
 * Mirrors the desktop's `InstalledAddon` shape but stores just the
 * essentials we'd otherwise re-derive from `manifest.resources`,
 * `manifest.types`, etc.
 */
@Serializable
data class InstalledAddon(
    /** Base URL of the addon (no trailing /manifest.json, no trailing slash). */
    val transportUrl: String,
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val logo: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<StremioResource> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class StremioResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)
