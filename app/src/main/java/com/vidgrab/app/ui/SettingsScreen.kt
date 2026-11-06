package com.vidgrab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vidgrab.app.BuildConfig
import com.vidgrab.app.data.HistoryStore
import com.vidgrab.app.engine.Engine
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ytdlpVersion by remember { mutableStateOf("checking...") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ytdlpVersion = withContext(Dispatchers.IO) {
            runCatching {
                Engine.await().getOrThrow()
                YoutubeDL.getInstance().version(context)
            }.getOrNull() ?: "unknown"
        }
    }

    fun updateEngine(channel: YoutubeDL.UpdateChannel) {
        if (busy) return
        busy = true
        status = "Updating yt-dlp..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Engine.await().getOrThrow()
                    YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, channel)
                }
            }
            busy = false
            status = result.fold(
                onSuccess = { updateStatus ->
                    when (updateStatus) {
                        YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated."
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date."
                        null -> "Update finished."
                    }
                },
                onFailure = { "Update failed: ${it.message?.take(300) ?: it.javaClass.simpleName}" }
            )
            ytdlpVersion = withContext(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() ?: ytdlpVersion
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Engine, storage and app info",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsCard(title = "Download engine") {
                InfoRow("App version", BuildConfig.VERSION_NAME)
                InfoRow("yt-dlp", ytdlpVersion)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { updateEngine(YoutubeDL.UpdateChannel.STABLE) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp).height(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Update stable")
                    }
                    OutlinedButton(
                        onClick = { updateEngine(YoutubeDL.UpdateChannel.NIGHTLY) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Nightly")
                    }
                }
                status?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SettingsCard(title = "Storage") {
                Text(
                    "Videos are saved to Downloads/VidGrab through Android's media store. " +
                        "Temporary files live in app storage and are removed after transfer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsCard(title = "History") {
                Text(
                    "The Downloads tab keeps a local list. Files stay on disk until you delete them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear download list")
                }
            }
        }

        item {
            SettingsCard(title = "About") {
                Text(
                    "VidGrab ${BuildConfig.VERSION_NAME} · GPL-3.0\n" +
                        "Powered by yt-dlp via youtubedl-android and FFmpeg. " +
                        "YouTube, Instagram, Facebook, TikTok and 1,800+ other sites are supported through yt-dlp extractors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear download list?") },
            text = { Text("This only clears the list. The saved video and audio files stay in Downloads/VidGrab.") },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.clear(context)
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
