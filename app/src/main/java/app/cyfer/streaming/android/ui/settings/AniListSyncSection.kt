package app.cyfer.streaming.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cyfer.streaming.android.data.anilist.AniListRepository
import app.cyfer.streaming.android.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListSyncSection() {
    val ctx = LocalContext.current
    val repo = remember { AniListRepository.get(ctx) }
    val session by repo.session.collectAsStateWithLifecycle(initialValue = AniListRepository.Session())
    val scope = rememberCoroutineScope()

    var tokenInput by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<AniListRepository.SyncResult?>(null) }

    Surface(
        color = CyferDarkSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "ANILIST",
                color = CyferTextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (session.isConnected) "Connected${session.username?.let { " as $it" } ?: ""}"
                    else "Not connected. Paste an AniList API token to import your planning list.",
                color = if (session.isConnected) CyferWhite else CyferTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!session.isConnected) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://anilist.co/settings/developer"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyferAccent),
                    border = BorderStroke(1.dp, CyferAccent),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Get token on anilist.co")
                }
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it; connectError = null },
                    label = { Text("Access token", color = CyferTextSecondary) },
                    placeholder = { Text("Bearer …", color = CyferTextTertiary) },
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CyferWhite,
                        unfocusedTextColor = CyferWhite,
                        cursorColor = CyferAccent,
                        focusedBorderColor = CyferAccent,
                        unfocusedBorderColor = CyferCardSurfaceLight,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                connectError?.let {
                    Text(it, color = CyferError, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        connecting = true
                        scope.launch {
                            val ok = repo.setAccessToken(tokenInput.trim())
                            connecting = false
                            connectError = if (ok) null else "Token rejected by AniList."
                            if (ok) tokenInput = ""
                        }
                    },
                    enabled = !connecting && tokenInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyferAccent, contentColor = CyferBlack),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(color = CyferBlack, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (connecting) "Connecting…" else "Connect AniList", fontWeight = FontWeight.Bold)
                }
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
                            CircularProgressIndicator(color = CyferAccent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(if (importing) "Importing…" else "Import planning list")
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
                    else "! ${result.error ?: "Sync failed"}"
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
