package com.vidgrab.app.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Accent palettes the user can pick in Settings → Appearance. */
enum class ThemeAccent(val label: String) {
    ORANGE("Orange"), MINT("Mint"), OCEAN("Ocean"), VIOLET("Violet"), SUNSET("Sunset"), ROSE("Rose"), LIME("Lime"), GOLD("Gold")
}

/** What the player does when a video ends (Settings → Player, and the player's own mode menu). */
enum class VideoEndMode(val label: String) {
    REPEAT_ALL("Play the next video"), AUTOPLAY_OFF("Stop"), REPEAT_ONE("Repeat the video"), SHUFFLE("Play a random video")
}

/** Quality the format sheet selects first. */
enum class PreferredQuality(val label: String) {
    BEST("Best available"), P1080("1080p"), P720("720p"), P480("480p"), AUDIO("Audio only")
}

/** Container the format sheet prefers when the site offers more than one. */
enum class PreferredFormat(val label: String) {
    MP4("MP4"), WEBM("WebM"), M4A("M4A audio"), ANY("Any")
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val accent: ThemeAccent = ThemeAccent.OCEAN,
    /** Pure black backgrounds in dark mode (saves battery on OLED screens). */
    val amoled: Boolean = false,
    /** Wallpaper-based colours on Android 12+. */
    val dynamicColor: Boolean = false,
    val downloadDir: String = "",
    val maxTasksWifi: Int = 4,
    val maxTasksMobile: Int = 2,
    /** KB/s, 0 = unlimited. */
    val speedLimitKbps: Int = 0,
    val allowMobileData: Boolean = true,
    /** Show a "link copied" suggestion on Home when the clipboard holds a video URL. */
    val clipboardDetection: Boolean = true,
    val defaultQuality: PreferredQuality = PreferredQuality.P720,
    val defaultFormat: PreferredFormat = PreferredFormat.MP4,
    val notifyProgress: Boolean = true,
    val notifyCompleted: Boolean = true,
    val notifyRecommended: Boolean = true,
    val notifyTools: Boolean = true,
    val toolbarNotification: Boolean = false,
    /** Salted PBKDF2 hash of the vault PIN, never the PIN itself. */
    val vaultPin: String? = null,
    val language: String = "en",
    /** Speed every video starts at (Settings → Player). */
    val playbackSpeed: Float = 1f,
    /** Remember where each video was left (capped list), for resuming and progress bars. */
    val playHistory: Boolean = true,
    /** Reopening a video continues where it was closed. Needs [playHistory]. */
    val resumePlayback: Boolean = true,
    /** Seconds a double tap (and the ±10s buttons) skips. */
    val seekStepSec: Int = 10,
    /** Speed while a finger holds the video. */
    val holdSpeed: Float = 2f,
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureSeek: Boolean = true,
    val gestureHoldSpeed: Boolean = true,
    val gestureDoubleTap: Boolean = true,
    val videoEndMode: VideoEndMode = VideoEndMode.REPEAT_ONE,
    val keepScreenOn: Boolean = true,
    /** A video's sound keeps playing after leaving the app when it doesn't go to picture-in-picture. */
    val backgroundPlay: Boolean = true,
    /** Leaving a playing video (back or home) keeps it going in a picture-in-picture window. */
    val autoPip: Boolean = true,
    /** Online videos: play the next suggested video when one ends (the player's autoplay switch). */
    val autoplayNext: Boolean = true,
    /** Crash reports + anonymous usage stats (official builds only). */
    val analyticsEnabled: Boolean = true,
)

data class Site(
    val id: Long = 0,
    val name: String,
    val url: String,
    val builtIn: Boolean = false,
    val position: Int = 0,
)

data class HistoryEntry(
    val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Where a played video was left. [key] is the file path for local files, the page URL for online videos. */
data class PlayHistoryEntry(
    val key: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val openedAt: Long,
)

/** A video saved with "Watch later". */
data class SavedVideo(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String,
    val durationSec: Long,
    val savedAt: Long = System.currentTimeMillis(),
)
