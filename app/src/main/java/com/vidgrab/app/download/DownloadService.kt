package com.vidgrab.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R
import com.vidgrab.app.data.HistoryStore
import com.vidgrab.app.engine.Engine
import com.vidgrab.app.model.ActiveDownload
import com.vidgrab.app.model.DownloadPhase
import com.vidgrab.app.model.HistoryEntry
import com.vidgrab.app.model.Quality
import com.vidgrab.app.util.formatEta
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val qualityName = intent.getStringExtra(EXTRA_QUALITY)
                val quality = Quality.entries.firstOrNull { it.name == qualityName } ?: Quality.BEST
                startDownload(url, intent.getStringExtra(EXTRA_TITLE), quality)
            }
            ACTION_CANCEL -> cancelDownload()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startDownload(url: String, title: String?, quality: Quality) {
        if (job?.isActive == true) return
        createChannel()
        cancelRequested = false
        startInForeground(buildNotification(title ?: "Video", "Preparing engine...", null, true, true))

        DownloadBus.update(
            ActiveDownload(title = title, quality = quality, phase = DownloadPhase.PREPARING)
        )

        job = scope.launch {
            var currentTitle = title
            try {
                Engine.await().getOrThrow()
                updateNotification(currentTitle ?: "Video", "Starting download...", null, true, true)

                val dir = File(getExternalFilesDir("downloads") ?: cacheDir, "").apply { mkdirs() }
                val before = dir.listFiles()?.mapNotNull { it?.name }?.toSet() ?: emptySet()
                val request = buildRequest(url, dir, quality)

                val response = YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, _ ->
                    val pct = progress.coerceIn(0f, 100f)
                    DownloadBus.update(
                        ActiveDownload(
                            title = currentTitle,
                            quality = quality,
                            phase = DownloadPhase.DOWNLOADING,
                            progress = pct,
                            etaSeconds = eta
                        )
                    )
                    val etaText = formatEta(eta)
                    val text = if (etaText.isEmpty()) "${pct.toInt()}%" else "${pct.toInt()}% - $etaText left"
                    updateNotification(currentTitle ?: "Video", text, pct, false, true)
                }

                currentTitle = currentTitle ?: parseTitle(response.out) ?: "Downloaded video"

                val produced = dir.listFiles()
                    ?.filter { file ->
                        file.isFile &&
                            !file.name.endsWith(".part") &&
                            !file.name.endsWith(".ytdl") &&
                            file.name !in before
                    }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException("yt-dlp finished but wrote no file")

                val output = MediaExporter.export(this@DownloadService, produced)
                val size = produced.length()
                produced.delete()

                val entry = HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    title = currentTitle ?: produced.nameWithoutExtension,
                    url = url,
                    fileName = output.displayName,
                    uri = output.uri.toString(),
                    mimeType = output.mimeType,
                    sizeBytes = size,
                    timestamp = System.currentTimeMillis(),
                    quality = quality.label
                )
                HistoryStore.add(this@DownloadService, entry)

                DownloadBus.update(
                    ActiveDownload(
                        title = entry.title,
                        quality = quality,
                        phase = DownloadPhase.FINISHED,
                        progress = 100f,
                        savedName = output.displayName,
                        savedUri = output.uri.toString(),
                        mimeType = output.mimeType
                    )
                )
                if (!cancelRequested) {
                    showFinishedNotification(entry)
                }
            } catch (t: Throwable) {
                val cancelled = cancelRequested ||
                    t is CancellationException ||
                    t is YoutubeDL.CanceledException
                if (cancelled) {
                    DownloadBus.update(
                        ActiveDownload(title = currentTitle, quality = quality, phase = DownloadPhase.CANCELLED)
                    )
                    if (!cancelRequested) return@launch
                    showTerminalNotification("Download cancelled", "The download was stopped.", false)
                } else {
                    val message = (t.message ?: t.javaClass.simpleName).take(700)
                    DownloadBus.update(
                        ActiveDownload(
                            title = currentTitle,
                            quality = quality,
                            phase = DownloadPhase.FAILED,
                            error = message
                        )
                    )
                    showTerminalNotification("Download failed", message, true)
                }
            } finally {
                if (!isJobCancelled()) {
                    endForeground()
                }
            }
        }
    }

    private fun isJobCancelled(): Boolean = cancelRequested

    private fun buildRequest(url: String, dir: File, quality: Quality): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("-o", File(dir, "%(title).80B.%(ext)s").absolutePath)
        when {
            quality.audioOnly -> {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }
            quality.height != null -> {
                val h = quality.height
                request.addOption(
                    "-f",
                    "bv*[height<=?$h][ext=mp4]+ba[ext=m4a]/b[height<=?$h][ext=mp4]/bv*[height<=?$h]+ba/b[height<=?$h]"
                )
                request.addOption("--merge-output-format", "mp4")
            }
            else -> {
                request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                request.addOption("--merge-output-format", "mp4")
            }
        }
        return request
    }

    private fun cancelDownload() {
        cancelRequested = true
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        job?.cancel()
        DownloadBus.update(null)
        showTerminalNotification("Download cancelled", "The download was stopped.", false)
        endForeground()
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress and results"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Float?,
        indeterminate: Boolean,
        cancellable: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(progress != null)
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress != null) {
            if (indeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, progress.toInt().coerceIn(0, 100), false)
            }
        }
        if (cancellable) {
            val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
            val cancelPending = PendingIntent.getService(
                this, 2, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Cancel", cancelPending)
        }
        return builder.build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Float?,
        indeterminate: Boolean,
        cancellable: Boolean
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIF_ID,
            buildNotification(title, text, progress, indeterminate, cancellable)
        )
    }

    private fun showFinishedNotification(entry: HistoryEntry) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(entry.uri), entry.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val contentPending = PendingIntent.getActivity(
            this, 3, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val appIntent = Intent(this, MainActivity::class.java)
        val appPending = PendingIntent.getActivity(
            this, 1, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Saved to Downloads/VidGrab")
            .setContentText(entry.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.title))
            .setAutoCancel(true)
            .setContentIntent(appPending)
            .addAction(0, "Open", contentPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private fun showTerminalNotification(title: String, text: String, failed: Boolean) {
        val appIntent = Intent(this, MainActivity::class.java)
        val appPending = PendingIntent.getActivity(
            this, 1, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(appPending)
            .setPriority(if (failed) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun endForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.vidgrab.app.action.START"
        const val ACTION_CANCEL = "com.vidgrab.app.action.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUALITY = "quality"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1101
        private const val PROCESS_ID = "vidgrab-download"

        fun start(context: Context, url: String, title: String?, quality: Quality) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_QUALITY, quality.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }
    }

    private fun parseTitle(stdout: String?): String? {
        if (stdout.isNullOrBlank()) return null
        return stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull { !it.startsWith("[") && !it.contains("Destination") }
    }
}
