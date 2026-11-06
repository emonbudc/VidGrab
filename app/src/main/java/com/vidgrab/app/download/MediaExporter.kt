package com.vidgrab.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object MediaExporter {
    data class Output(val uri: Uri, val displayName: String, val mimeType: String)

    fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "mp4" -> "video/mp4"
        "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }

    fun export(context: Context, source: File): Output {
        val mime = mimeTypeFor(source.extension)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportScoped(context, source, mime)
        } else {
            exportLegacy(context, source, mime)
        }
    }

    private fun exportScoped(context: Context, source: File, mime: String): Output {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VidGrab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open the Downloads file for writing")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return Output(uri, source.name, mime)
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(context: Context, source: File, mime: String): Output {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outDir = File(downloads, "VidGrab").apply { mkdirs() }
        val destination = File(outDir, source.name)
        source.copyTo(destination, overwrite = true)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destination
        )
        return Output(uri, source.name, mime)
    }
}
