package com.vidgrab.app.desktop.cli

import com.vidgrab.app.core.extractor.OkHttpDownloader
import com.vidgrab.app.core.model.DownloadOption
import com.vidgrab.app.core.model.FormatKind
import com.vidgrab.app.core.model.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class ToolMissingException(message: String) : IOException(message)

/**
 * Minimal desktop download pipeline: ranged HTTP with resume for direct files, ffmpeg for HLS,
 * muxing separated video/audio tracks, and audio extraction.
 */
class MediaDownloader(
    private val client: OkHttpClient,
    private val toolDir: File? = null,
) {

    suspend fun download(
        option: DownloadOption,
        title: String,
        outDir: File,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("couldn't create ${outDir.path}")

        val base = com.vidgrab.app.core.common.Urls.safeFileName(fileTitle(title, option))
        val target = uniqueFile(outDir, base, outputExtension(option))
        val temps = mutableListOf<File>()

        try {
            when {
                option.protocol == StreamProtocol.HLS && option.audioUrl == null && !option.extractAudio -> {
                    runFfmpeg(listOf("-y", "-i", option.url, "-c", "copy", target.absolutePath))
                }

                option.audioUrl != null -> {
                    val video = File(outDir, ".${target.name}.video").also { temps += it }
                    val audio = File(outDir, ".${target.name}.audio").also { temps += it }
                    if (option.protocol == StreamProtocol.HLS) {
                        runFfmpeg(listOf("-y", "-i", option.url, "-c", "copy", video.absolutePath))
                    } else {
                        fetch(option.url, video, headers, onProgress)
                    }
                    fetch(option.audioUrl, audio, headers, onProgress)
                    if (option.extractAudio) {
                        extractAudio(audio, target)
                    } else {
                        try {
                            merge(video, audio, target)
                        } catch (e: IOException) {
                            if (target.extension != "mp4") throw e
                            // VP9/Opus into MP4 can fail on older ffmpeg builds; Matroska always takes them.
                            val fallback = File(target.parentFile, target.nameWithoutExtension + ".mkv")
                            merge(video, audio, fallback)
                            target.delete()
                            return@withContext fallback
                        }
                    }
                }

                option.extractAudio -> {
                    val source = File(outDir, ".${target.name}.source").also { temps += it }
                    fetch(option.url, source, headers, onProgress)
                    extractAudio(source, target)
                }

                else -> fetch(option.url, target, headers, onProgress)
            }
        } finally {
            temps.forEach { it.delete() }
        }
        target
    }

    private fun fetch(url: String, target: File, headers: Map<String, String>, onProgress: (Long, Long) -> Unit) {
        val part = File(target.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", OkHttpDownloader.USER_AGENT)
        headers.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true)) builder.header(name, value)
        }
        if (have > 0) builder.header("Range", "bytes=$have-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val append = have > 0 && response.code == 206
            if (!append) {
                have = 0
                part.delete()
            }
            val length = response.body.contentLength()
            val total = if (length >= 0) length + have else -1
            response.body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = have
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    private fun merge(video: File, audio: File, output: File) {
        val args = mutableListOf("-y", "-i", video.path, "-i", audio.path, "-c", "copy")
        if (output.extension.lowercase(Locale.ROOT) in setOf("mp4", "m4v", "mov")) {
            args += listOf("-movflags", "+faststart")
        }
        args += output.path
        runFfmpeg(args)
    }

    private fun extractAudio(input: File, output: File) {
        runFfmpeg(listOf("-y", "-i", input.path, "-vn", "-c:a", "aac", "-b:a", "192k", output.path))
    }

    private fun runFfmpeg(args: List<String>) {
        val command = listOf(ffmpeg()) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            val reason = log.lineSequence().lastOrNull { it.isNotBlank() } ?: "unknown error"
            throw IOException("ffmpeg failed (exit $code): $reason")
        }
    }

    private fun ffmpeg(): String {
        val exe = if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) "ffmpeg.exe" else "ffmpeg"
        toolDir?.resolve(exe)?.takeIf { it.isFile }?.let { return it.absolutePath }
        val available = runCatching {
            val process = ProcessBuilder(exe, "-version").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)
        if (!available) {
            throw ToolMissingException("ffmpeg not found. Install it or pass --tools <dir> containing $exe")
        }
        return exe
    }

    private fun fileTitle(title: String, option: DownloadOption): String {
        val suffix = when {
            option.height > 0 -> "${option.height}p"
            option.bitrate > 0 -> "${option.bitrate}k"
            else -> ""
        }
        return if (suffix.isEmpty()) title else "$title [$suffix]"
    }

    private fun outputExtension(option: DownloadOption): String = when {
        option.ext.isNotBlank() -> option.ext
        option.kind == FormatKind.AUDIO -> "m4a"
        option.kind == FormatKind.IMAGE -> "jpg"
        else -> "mp4"
    }

    private fun uniqueFile(dir: File, base: String, extension: String): File {
        var file = File(dir, "$base.$extension")
        var copy = 2
        while (file.exists()) file = File(dir, "$base (${copy++}).$extension")
        return file
    }
}
