package com.vidgrab.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

object YtDlpUpdater {
    enum class Channel(val url: String) {
        STABLE("https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"),
        NIGHTLY("https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp"),
        MASTER("https://github.com/yt-dlp/yt-dlp-master-builds/releases/latest/download/yt-dlp")
    }

    data class Result(val version: String?)

    suspend fun update(context: Context, channel: Channel): Result = withContext(Dispatchers.IO) {
        val target = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "yt-dlp.new")
        var connection: HttpURLConnection? = null
        try {
            var url = URL(channel.url)
            var redirects = 0
            while (true) {
                val current = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 90_000
                    setRequestProperty("User-Agent", "VidGrab")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                connection = current
                val code = current.responseCode
                if (code in 300..399) {
                    val location = current.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location (HTTP $code)")
                    current.disconnect()
                    connection = null
                    url = URL(url, location)
                    redirects++
                    if (redirects > 6) throw IllegalStateException("Too many redirects")
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code while downloading yt-dlp")
                current.inputStream.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                break
            }

            if (temp.length() < 1_000_000) {
                throw IllegalStateException("Downloaded yt-dlp is too small (${temp.length()} bytes)")
            }
            val head = ByteArray(4096)
            val count = temp.inputStream().use { it.read(head) }.coerceAtLeast(0)
            val start = String(head, 0, count, Charsets.ISO_8859_1)
            if (!start.startsWith("#!")) {
                throw IllegalStateException("Downloaded file is not a yt-dlp zipapp")
            }

            val version = readVersion(temp)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                .edit()
                .putString("dlpVersion", version ?: "direct-${System.currentTimeMillis()}")
                .putString("dlpVersionName", version ?: "direct")
                .apply()
            Result(version)
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    private fun readVersion(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("yt_dlp/version.py") ?: return@use null
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            Regex("""__version__\s*=\s*['"]([^'"]+)['"]""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
        }
    }.getOrNull()
}
