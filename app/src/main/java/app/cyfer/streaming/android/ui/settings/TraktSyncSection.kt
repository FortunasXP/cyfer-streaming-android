package app.cyfer.streaming.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.trakt.TraktDeviceCode
import app.cyfer.streaming.android.data.trakt.TraktRepository
import app.cyfer.streaming.android.data.trakt.TraktSession
import app.cyfer.streaming.android.data.trakt.TraktSyncResult
import app.cyfer.streaming.android.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TraktSyncSection() {
    val ctx = LocalContext.current
    val repo = remember { TraktRepository.get(ctx) }
    val session by repo.session.collectAsStateWithLifecycle(initialValue = TraktSession())
    val scope = rememberCoroutineScope()

    var connectCode by remember { mutableStateOf<TraktDeviceCode?>(null) }
    var importing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<TraktSyncResult?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Surface(
            color = CyferDarkSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "TRAKT",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (session.isConnected)
                        "Connected${session.username?.let { " as $it" } ?: ""}"
                    else
                        "Not connected. Sign in to sync your watchlist and history across desktop + mobile.",
                    color = if (session.isConnected) CyferWhite else CyferTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!session.isConnected) {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { repo.requestDeviceCode() }
                                    .onSuccess { code ->
                                        connectCode = code
                                        // Poll in the background; close the dialog when done.
                                        scope.launch {
                                            val ok = runCatching { repo.pollForToken(code) }.getOrDefault(false)
                                            if (ok) connectCode = null
                                        }
                                    }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyferAccent,
                            contentColor = CyferBlack,
                        ),
                    ) { Text("Connect Trakt", fontWeight = FontWeight.Bold) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                importing = true
                                scope.launch {
                                    lastResult = repo.importWatchlist()
                                    importing = false
                                }
                            },
                            enabled = !importing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferAccent),
                            border = BorderStroke(1.dp, CyferAccent),
                        ) {
                            if (importing) {
                                CircularProgressIndicator(color = CyferAccent, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(if (importing) "Importing…" else "Import watchlist")
                        }
                        OutlinedButton(
                            onClick = { scope.launch { repo.disconnect() } },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferError),
                            border = BorderStroke(1.dp, CyferError),
                        ) { Text("Disconnect") }
                    }
                    lastResult?.let { result ->
                        val txt = if (result.ok)
                            "✓ Imported ${result.imported} item${if (result.imported == 1) "" else "s"}. Skipped ${result.skipped}."
                        else
                            "! ${result.error ?: "Sync failed"}"
                        Text(
                            text = txt,
                            color = if (result.ok) CyferAccent else CyferError,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    // Device-code activation dialog.
    connectCode?.let { code ->
        TraktConnectDialog(code = code, onCancel = { connectCode = null })
    }
}

@Composable
private fun TraktConnectDialog(code: TraktDeviceCode, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CyferDarkSurface,
        titleContentColor = CyferWhite,
        textContentColor = CyferTextSecondary,
        title = { Text("Activate Trakt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "1. Open ${code.verificationUrl} on any device.",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "2. Enter this code:",
                    color = CyferTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = code.userCode,
                        color = CyferAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(code.userCode)) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferAccent),
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy code") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferAccent),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = CyferAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Text(
                        text = "Waiting for approval…",
                        color = CyferTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = CyferTextSecondary) }
        },
    )
}
