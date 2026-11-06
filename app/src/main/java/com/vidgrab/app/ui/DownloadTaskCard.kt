package com.vidgrab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vidgrab.app.model.DownloadTask
import com.vidgrab.app.model.TaskStatus
import com.vidgrab.app.util.formatEta

@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusLabel = when (task.status) {
        TaskStatus.QUEUED -> "Queued"
        TaskStatus.PREPARING -> "Preparing"
        TaskStatus.DOWNLOADING -> "Downloading"
        TaskStatus.PAUSED -> "Paused"
        TaskStatus.FAILED -> "Failed"
    }
    val container = when (task.status) {
        TaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        TaskStatus.PAUSED -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title ?: task.url,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${task.quality.label}  ·  $statusLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                }
            }

            when (task.status) {
                TaskStatus.QUEUED -> {
                    Text(
                        "Waiting for the current download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TaskStatus.PREPARING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Starting yt-dlp...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TaskStatus.DOWNLOADING -> {
                    val percent = task.progress.toInt().coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    val eta = formatEta(task.etaSeconds)
                    Text(
                        "$percent%" + if (eta.isEmpty()) "" else "  ·  $eta left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TaskStatus.PAUSED -> {
                    LinearProgressIndicator(
                        progress = { task.progress.toInt().coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Paused at ${task.progress.toInt().coerceIn(0, 100)}% - tap Resume to continue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TaskStatus.FAILED -> {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        task.error ?: "Download failed",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (task.status) {
                    TaskStatus.QUEUED, TaskStatus.PREPARING, TaskStatus.DOWNLOADING -> {
                        TextButton(onClick = onPause) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause")
                        }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    TaskStatus.PAUSED -> {
                        TextButton(onClick = onResume) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Resume")
                        }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    TaskStatus.FAILED -> {
                        TextButton(onClick = onResume) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                        TextButton(onClick = onCancel) { Text("Remove") }
                    }
                }
            }
        }
    }
}
