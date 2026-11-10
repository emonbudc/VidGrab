package com.vidgrab.app.core.download

import android.net.Uri
import com.vidgrab.app.core.model.SubtitleTrack
import com.vidgrab.app.core.model.subtitleExtForMime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SubtitleDownloader(private val http: OkHttpClient) {

    suspend fun download(track: SubtitleTrack, videoFile: File, headers: Map<String, String>): SubtitleTrack? =
        withContext(Dispatchers.IO) {
            if (track.url.toHttpUrlOrNull() == null) return@withContext null
            val part = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.subtitle.part")
            val client = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(track.url).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    val body = response.body
                    if (body.contentLength() > MAX_BYTES) throw IOException("Subtitle file is too large")
                    part.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_BYTES) throw IOException("Subtitle file is too large")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                if (part.length() == 0L) throw IOException("Empty subtitle file")
                val headBytes = ByteArray(4096)
                val count = part.inputStream().use { it.read(headBytes) }.coerceAtLeast(0)
                val head = String(headBytes, 0, count, Charsets.UTF_8)
                val mime = detectMime(head) ?: track.mimeType
                if (!valid(head)) return@withContext null
                val ext = subtitleExtForMime(mime)
                val target = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.${safeLanguage(track.language)}.$ext")
                if (target.exists() && target.length() > 0) {
                    part.delete()
                    return@withContext track.copy(url = Uri.fromFile(target).toString(), mimeType = mime)
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                track.copy(url = Uri.fromFile(target).toString(), mimeType = mime)
            } catch (e: Exception) {
                part.delete()
                throw e
            }
        }

    private fun safeLanguage(language: String): String =
        language.lowercase().replace(Regex("[^a-z0-9-]"), "_").trim('_').ifBlank { "und" }

    private fun detectMime(head: String): String? {
        val text = head.trimStart('\uFEFF', ' ', '\n', '\r')
        return when {
            text.startsWith("WEBVTT", ignoreCase = true) -> "text/vtt"
            text.startsWith("[Script Info]", ignoreCase = true) -> "text/x-ssa"
            text.startsWith("<") && (text.contains("<tt") || text.contains("ttml")) -> "application/ttml+xml"
            text.contains("-->") -> "application/x-subrip"
            else -> null
        }
    }

    private fun valid(head: String): Boolean {
        val text = head.trimStart('\uFEFF', ' ', '\n', '\r').lowercase()
        if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("{")) return false
        return text.startsWith("webvtt") || text.contains("-->") || text.startsWith("<") ||
            text.startsWith("[script info]") || text.firstOrNull()?.isDigit() == true
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
