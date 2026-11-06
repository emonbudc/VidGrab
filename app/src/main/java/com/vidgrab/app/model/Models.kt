package com.vidgrab.app.model

enum class Quality(val label: String, val height: Int? = null, val audioOnly: Boolean = false) {
    BEST("Best"),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    MP3("Audio MP3", audioOnly = true)
}

data class VideoInfoUi(
    val title: String,
    val thumbnail: String?,
    val durationSeconds: Int,
    val uploader: String?,
    val webpageUrl: String
)

enum class TaskStatus { QUEUED, PREPARING, DOWNLOADING, PAUSED, FAILED }

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String?,
    val quality: Quality,
    val status: TaskStatus,
    val progress: Float = 0f,
    val etaSeconds: Long = 0L,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryEntry(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val quality: String
)
