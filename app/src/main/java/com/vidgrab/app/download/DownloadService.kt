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
import com.vidgrab.app.data.DownloadRepository
import com.vidgrab.app.data.HistoryStore
import com.vidgrab.app.engine.Engine
import com.vidgrab.app.model.DownloadTask
import com.vidgrab.app.model.HistoryEntry
import com.vidgrab.app.model.Quality
import com.vidgrab.app.model.TaskStatus
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
    private var worker: Job? = null

    @Volatile
    private var currentTaskId: String? = null

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (worker?.isActive != true) {
                    startInForeground("VidGrab", "Preparing download...", null, true, null)
                }
                startWorker()
            }
            ACTION_RESUME -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    DownloadRepository.update(this, taskId, true) { task ->
                        if (task.status == TaskStatus.PAUSED || task.status == TaskStatus.FAILED) {
                            task.copy(status = TaskStatus.QUEUED, error = null, etaSeconds = 0L)
                        } else {
                            task
                        }
                    }
                }
                val active = DownloadRepository.find(currentTaskId)
                if (worker?.isActive == true && active != null) {
                    startInForeground(
                        active.title ?: "Video",
                        statusText(active),
                        active.progress,
                        active.status != TaskStatus.DOWNLOADING,
                        active.id
                    )
                } else {
                    startInForeground("VidGrab", "Resuming download...", null, true, taskId)
                }
                startWorker()
            }
            ACTION_PAUSE -> pauseTask(intent.getStringExtra(EXTRA_TASK_ID))
            ACTION_CANCEL -> cancelTask(intent.getStringExtra(EXTRA_TASK_ID))
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val task = DownloadRepository.claimNext(this@DownloadService) ?: break
                currentTaskId = task.id
                pauseRequested = false
                cancelRequested = false
                runTask(task)
                currentTaskId = null
            }
            endForeground()
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        val id = task.id
        var lastPersistedProgress = task.progress
        try {
            DownloadRepository.update(this, id, true) { it.copy(status = TaskStatus.PREPARING, error = null) }
            updateActiveNotification(id, task.title ?: "Video", "Preparing engine...", null, true)
            Engine.await().getOrThrow()
            if (pauseRequested) throw CancellationException("paused")

            val dir = File(getExternalFilesDir("downloads") ?: cacheDir, "").apply { mkdirs() }
            val before = dir.listFiles()?.mapNotNull { it?.name }?.toSet() ?: emptySet()
            val request = buildRequest(task.url, dir, task.quality)
            DownloadRepository.update(this, id, false) { it.copy(status = TaskStatus.DOWNLOADING, progress = 0f) }

            YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, _ ->
                val percent = progress.coerceIn(0f, 100f)
                val persist = percent - lastPersistedProgress >= 5f
                if (persist) lastPersistedProgress = percent
                DownloadRepository.update(this, id, persist) {
                    it.copy(status = TaskStatus.DOWNLOADING, progress = percent, etaSeconds = eta)
                }
                val etaText = formatEta(eta)
                val text = if (etaText.isEmpty()) "${percent.toInt()}%" else "${percent.toInt()}% - $etaText left"
                updateActiveNotification(id, task.title ?: "Video", text, percent, false)
            }

            val produced = dir.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl") &&
                        file.name !in before
                }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("yt-dlp finished but wrote no file")

            val output = MediaExporter.export(this, produced)
            val size = produced.length()
            produced.delete()

            val entry = HistoryEntry(
                id = UUID.randomUUID().toString(),
                title = task.title ?: produced.nameWithoutExtension,
                url = task.url,
                fileName = output.displayName,
                uri = output.uri.toString(),
                mimeType = output.mimeType,
                sizeBytes = size,
                timestamp = System.currentTimeMillis(),
                quality = task.quality.label
            )
            HistoryStore.add(this, entry)
            DownloadRepository.remove(this, id)
            showFinishedNotification(entry)
        } catch (t: Throwable) {
            if (pauseRequested) {
                DownloadRepository.update(this, id, true) {
                    it.copy(status = TaskStatus.PAUSED, etaSeconds = 0L)
                }
                val current = DownloadRepository.find(id)
                showPausedNotification(id, task.title ?: "Video", current?.progress ?: task.progress)
            } else if (cancelRequested) {
                DownloadRepository.remove(this, id)
                showTerminalNotification("Download cancelled", "The download was stopped.")
            } else if (t is CancellationException) {
                DownloadRepository.update(this, id, true) {
                    it.copy(status = TaskStatus.PAUSED, etaSeconds = 0L)
                }
            } else {
                val message = (t.message ?: t.javaClass.simpleName).take(700)
                DownloadRepository.update(this, id, true) {
                    it.copy(status = TaskStatus.FAILED, progress = 0f, error = message)
                }
                showFailedNotification(id, task.title ?: "Video", message)
            }
        }
    }

    private fun buildRequest(url: String, dir: File, quality: Quality): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("--continue")
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

    private fun pauseTask(taskId: String?) {
        if (taskId == null) return
        val isCurrent = taskId == currentTaskId
        if (isCurrent) {
            pauseRequested = true
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        }
        DownloadRepository.update(this, taskId, true) { task ->
            if (task.status == TaskStatus.QUEUED ||
                task.status == TaskStatus.PREPARING ||
                task.status == TaskStatus.DOWNLOADING
            ) {
                task.copy(status = TaskStatus.PAUSED, etaSeconds = 0L)
            } else {
                task
            }
        }
        val task = DownloadRepository.find(taskId)
        if (isCurrent && task != null) {
            showPausedNotification(taskId, task.title ?: "Video", task.progress)
        }
        if (!isCurrent && worker?.isActive != true) {
            endForeground()
        }
    }

    private fun cancelTask(taskId: String?) {
        if (taskId == null) return
        val isCurrent = taskId == currentTaskId
        if (isCurrent) {
            cancelRequested = true
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        }
        DownloadRepository.remove(this, taskId)
        if (isCurrent || worker?.isActive != true) {
            showTerminalNotification("Download cancelled", "The download was stopped.")
            endForeground()
        }
    }

    private fun statusText(task: DownloadTask): String = when (task.status) {
        TaskStatus.QUEUED -> "Queued"
        TaskStatus.PREPARING -> "Preparing engine..."
        TaskStatus.DOWNLOADING -> {
            val eta = formatEta(task.etaSeconds)
            if (eta.isEmpty()) "${task.progress.toInt()}%" else "${task.progress.toInt()}% - $eta left"
        }
        TaskStatus.PAUSED -> "Paused at ${task.progress.toInt()}%"
        TaskStatus.FAILED -> "Failed"
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

    private fun baseBuilder(title: String, text: String, ongoing: Boolean): NotificationCompat.Builder {
        val appIntent = Intent(this, MainActivity::class.java)
        val appPending = PendingIntent.getActivity(
            this, 1, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setContentIntent(appPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun activeNotification(
        taskId: String?,
        title: String,
        text: String,
        progress: Float?,
        indeterminate: Boolean
    ): Notification {
        val builder = baseBuilder(title, text, true)
        if (progress != null) {
            if (indeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, progress.toInt().coerceIn(0, 100), false)
            }
        } else {
            builder.setProgress(0, 0, true)
        }
        if (taskId != null) {
            builder.addAction(0, "Pause", servicePending(ACTION_PAUSE, taskId, 10))
            builder.addAction(0, "Cancel", servicePending(ACTION_CANCEL, taskId, 11))
        }
        return builder.build()
    }

    private fun pausedNotification(taskId: String, title: String, progress: Float): Notification {
        val builder = baseBuilder(title, "Paused at ${progress.toInt()}%", false)
            .setProgress(100, progress.toInt().coerceIn(0, 100), false)
        builder.addAction(0, "Resume", resumePending(taskId, 12))
        builder.addAction(0, "Cancel", servicePending(ACTION_CANCEL, taskId, 11))
        return builder.build()
    }

    private fun failedNotification(taskId: String, title: String, message: String): Notification {
        val builder = baseBuilder(title, "Download failed", false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        builder.addAction(0, "Retry", resumePending(taskId, 12))
        builder.addAction(0, "Cancel", servicePending(ACTION_CANCEL, taskId, 11))
        return builder.build()
    }

    private fun finishedNotification(entry: HistoryEntry): Notification {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(entry.uri), entry.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val contentPending = PendingIntent.getActivity(
            this, 3, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return baseBuilder("Saved to Downloads/VidGrab", entry.title, false)
            .setAutoCancel(true)
            .addAction(0, "Open", contentPending)
            .build()
    }

    private fun terminalNotification(title: String, text: String, failed: Boolean): Notification =
        baseBuilder(title, text, false)
            .setAutoCancel(true)
            .setPriority(
                if (failed) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW
            )
            .build()

    private fun updateActiveNotification(
        taskId: String,
        title: String,
        text: String,
        progress: Float?,
        indeterminate: Boolean
    ) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            activeNotification(taskId, title, text, progress, indeterminate)
        )
    }

    private fun showPausedNotification(taskId: String, title: String, progress: Float) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            pausedNotification(taskId, title, progress)
        )
    }

    private fun showFailedNotification(taskId: String, title: String, message: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            failedNotification(taskId, title, message)
        )
    }

    private fun showFinishedNotification(entry: HistoryEntry) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finishedNotification(entry)
        )
    }

    private fun showTerminalNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            terminalNotification(title, text, false)
        )
    }

    private fun startInForeground(
        title: String,
        text: String,
        progress: Float?,
        indeterminate: Boolean,
        taskId: String?
    ) {
        createChannel()
        val notification = activeNotification(taskId, title, text, progress, indeterminate)
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

    private fun servicePending(action: String, taskId: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resumePending(taskId: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_RESUME
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    companion object {
        const val ACTION_START = "com.vidgrab.app.action.START"
        const val ACTION_RESUME = "com.vidgrab.app.action.RESUME"
        const val ACTION_PAUSE = "com.vidgrab.app.action.PAUSE"
        const val ACTION_CANCEL = "com.vidgrab.app.action.CANCEL"
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1101
        private const val PROCESS_ID = "vidgrab-download"

        fun startTask(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun resume(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }
}
