package app.cyfer.streaming.android.data.trakt

/**
 * Bundled Trakt OAuth credentials — shared with the desktop build's
 * `resources/app-defaults.json`. Trakt OAuth secrets are public by design
 * (the client_secret is required for the device-code flow but never
 * grants access on its own), so baking them into the APK is the same
 * model the desktop already uses.
 */
object TraktConfig {
    const val API_BASE = "https://api.trakt.tv"
    const val USER_AGENT = "Cyfer Streaming Android/0.1"
    const val DEVICE_VERIFICATION_URL = "https://trakt.tv/activate"

    const val CLIENT_ID = "e15e5a61432266fd72c9357d302fdef8c50646b786c7be6937bf784b4b39a5fa"
    const val CLIENT_SECRET = "6469f25b4e68e08aba88e64301c7d571cf13d5bf919951429d0e94f61c34d3a4"

    /** Max poll wait while the user enters the code on trakt.tv/activate. */
    const val DEVICE_POLL_TIMEOUT_MS = 600_000L  // 10 min
    /** Page size for /sync/watchlist + /sync/history. */
    const val PAGE_LIMIT = 100
    /** Max pages we walk when importing — safety bound. */
    const val MAX_IMPORT_PAGES = 30
}
