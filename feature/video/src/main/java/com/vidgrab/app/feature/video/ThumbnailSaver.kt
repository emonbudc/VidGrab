package com.vidgrab.app.feature.video

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vidgrab.app.core.common.Urls
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads a thumbnail into Pictures/VidGrab and returns its file name, or null when it couldn't be saved. */
internal suspend fun saveThumbnail(context: Context, url: String?, title: String): String? = withContext(Dispatchers.IO) {
    val source = url?.takeIf { it.isNotBlank() } ?: return@withContext null
    val bitmap = runCatching {
        (URL(source).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            inputStream.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull() ?: return@withContext null
    val name = Urls.safeFileName(title.ifBlank { "thumbnail" }) + "_thumb_" + System.currentTimeMillis() / 1000 + ".jpg"
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VidGrab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                ?: return@runCatching null
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VidGrab").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg"), null)
        }
        name
    }.getOrNull()
}
