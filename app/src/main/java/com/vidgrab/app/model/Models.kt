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

enum class DownloadPhase { PREPARING, DOWNLOADING, FINISHED, FAILED, CANCELLED }

data class ActiveDownload(
    val title: String?,
    val quality: Quality,
    val phase: DownloadPhase,
    val progress: Float = 0f,
    val etaSeconds: Long = 0L,
    val error: String? = null,
    val savedName: String? = null,
    val savedUri: String? = null,
    val mimeType: String? = null
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
