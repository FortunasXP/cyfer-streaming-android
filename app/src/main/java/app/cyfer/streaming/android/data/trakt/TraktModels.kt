package app.cyfer.streaming.android.data.trakt

import kotlinx.serialization.Serializable

/**
 * Persisted Trakt session — stored in its own DataStore separate from
 * AppSettings so token rotation never collides with a user-settings
 * write. Empty fields = not connected.
 */
@Serializable
data class TraktSession(
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    /** Unix millis. 0 = no token. */
    val expiresAt: Long = 0L,
    val scope: String = "public",
    val username: String? = null,
    val updatedAt: Long = 0L,
) {
    val isConnected: Boolean get() = accessToken.isNotBlank()
    val isExpired: Boolean get() = expiresAt in 1..System.currentTimeMillis()
}

/** Response shape of `POST /oauth/device/code`. */
data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** Outcome of one import / export sync — surfaced in the UI. */
data class TraktSyncResult(
    val ok: Boolean,
    val imported: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
)
