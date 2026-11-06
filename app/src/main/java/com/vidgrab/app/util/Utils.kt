package com.vidgrab.app.util

import java.util.Locale
import kotlin.math.abs

private val urlRegex = Regex("""https?://[^\s<>"']+""")

fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = urlRegex.find(text)?.value ?: return null
    return match.trimEnd('.', ',', ')', ']', '}', ';', '"', '\'', '!', '?')
}

fun isHttpUrl(text: String): Boolean {
    val trimmed = text.trim()
    return (trimmed.startsWith("http://") || trimmed.startsWith("https://")) &&
        trimmed.length > 8 && trimmed.contains(".")
}

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B"
    else String.format(Locale.US, "%.1f %s", value, units[index])
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return ""
    val s = abs(seconds)
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}
