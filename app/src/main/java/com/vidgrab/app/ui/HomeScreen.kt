package com.vidgrab.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vidgrab.app.download.DownloadBus
import com.vidgrab.app.download.DownloadService
import com.vidgrab.app.model.ActiveDownload
import com.vidgrab.app.model.DownloadPhase
import com.vidgrab.app.model.Quality
import com.vidgrab.app.model.VideoInfoUi
import com.vidgrab.app.util.extractUrl
import com.vidgrab.app.util.formatDuration
import com.vidgrab.app.util.formatEta
import com.vidgrab.app.util.isHttpUrl

@Composable
fun HomeScreen(
    pendingUrl: String?,
    onUrlConsumed: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val active by DownloadBus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clipboardLink by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        clipboardLink = extractUrl(text)?.takeIf { isHttpUrl(it) }
    }

    LaunchedEffect(pendingUrl) {
        if (!pendingUrl.isNullOrBlank()) {
            viewModel.loadUrl(pendingUrl)
            onUrlConsumed()
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
                    text = "VidGrab",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Paste. Pick. Grab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            UrlInputCard(
                url = ui.url,
                loading = ui.loading,
                onUrlChange = viewModel::onUrlChange,
                onPaste = { clipboardLink?.let(viewModel::loadUrl) },
                onFetch = viewModel::fetch
            )
        }

        if (clipboardLink != null && ui.url.isBlank()) {
            item {
                AssistChip(
                    onClick = { clipboardLink?.let(viewModel::loadUrl) },
                    label = { Text("Use copied link") },
                    leadingIcon = {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }

        ui.error?.let { message ->
            item { ErrorCard(message) }
        }

        ui.info?.let { info ->
            item { VideoCard(info = info, selected = ui.selected, onSelect = viewModel::selectQuality) }
            item {
                val busy = active?.phase == DownloadPhase.PREPARING ||
                    active?.phase == DownloadPhase.DOWNLOADING
                Button(
                    onClick = { viewModel.download(ui.selected) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download ${ui.selected.label}")
                }
            }
        }

        active?.let { download ->
            item {
                DownloadStatusCard(
                    download = download,
                    onCancel = { DownloadService.cancel(context) },
                    onOpen = { uri, mime -> openMedia(context, uri, mime) },
                    onDismiss = { DownloadBus.update(null) }
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sites", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "YouTube, Instagram, Facebook, TikTok and 1,800+ more through yt-dlp. " +
                            "Login-only or region-locked videos can fail; updating yt-dlp in Settings fixes most extractor breakage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun UrlInputCard(
    url: String,
    loading: Boolean,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onFetch: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://youtube.com/watch?v=...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onFetch() }),
                trailingIcon = {
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                    }
                }
            )
            Button(
                onClick = onFetch,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch video")
                }
            }
        }
    }
}

@Composable
private fun VideoCard(
    info: VideoInfoUi,
    selected: Quality,
    onSelect: (Quality) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            if (!info.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = info.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    info.uploader?.takeIf { it.isNotBlank() }?.let { add(it) }
                    formatDuration(info.durationSeconds).takeIf { it.isNotEmpty() }?.let { add(it) }
                }.joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Quality.entries.forEach { quality ->
                        FilterChip(
                            selected = quality == selected,
                            onClick = { onSelect(quality) },
                            label = { Text(quality.label) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusCard(
    download: ActiveDownload,
    onCancel: () -> Unit,
    onOpen: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (download.phase) {
                DownloadPhase.FINISHED -> MaterialTheme.colorScheme.primaryContainer
                DownloadPhase.FAILED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (download.phase) {
                DownloadPhase.PREPARING -> {
                    Text("Preparing engine...", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                DownloadPhase.DOWNLOADING -> {
                    val percent = download.progress.toInt().coerceIn(0, 100)
                    Text("Downloading  $percent%", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { download.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val eta = formatEta(download.etaSeconds)
                    Text(
                        if (eta.isEmpty()) "Working..." else "About $eta left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                DownloadPhase.FINISHED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Saved to Downloads/VidGrab", fontWeight = FontWeight.SemiBold)
                    }
                    download.savedName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                download.savedUri?.let { uri ->
                                    onOpen(uri, download.mimeType ?: "video/mp4")
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open")
                        }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                }
                DownloadPhase.FAILED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Error, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download failed", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        download.error ?: "Unknown error",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
                DownloadPhase.CANCELLED -> {
                    Text("Download cancelled", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Error, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Could not fetch video", fontWeight = FontWeight.SemiBold)
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
