package com.vidgrab.app.core.download

import android.content.Context
import com.vidgrab.app.core.database.DownloadDao
import com.vidgrab.app.core.database.DownloadEntity
import com.vidgrab.app.core.model.ContentType
import com.vidgrab.app.core.model.DownloadStatus
import com.vidgrab.app.core.model.StreamProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Merges, remuxes and publishes finished downloads, and cleans up their partial files. */
internal class DownloadPostProcessor(
    private val context: Context,
    private val dao: DownloadDao,
    private val notifier: DownloadNotifier,
) {
    private val mergeLock = Mutex()

    /** Merges / remuxes / renames the finished parts into the final file, which is returned (its extension may change). */
    suspend fun finish(e: DownloadEntity, target: File, base: String): File {
        val videoPart = File("$base.part")
        val audioPart = File("$base.audio.part")
        val isPrivate = base != e.filePath
        val isHls = e.protocol == StreamProtocol.HLS
        // Intermediate results stay next to the parts; only the finished file is moved to the download folder.
        val work = if (isPrivate) File("$base.out.${target.extension}") else target
        val videoOut = if (e.extractAudio) File("$base.video.mp4") else work
        if (e.audioUrl != null || isHls || e.extractAudio) {
            dao.updateStatus(e.id, DownloadStatus.MERGING)
            notifier.refresh(true)
        }
        val started = System.currentTimeMillis()
        var out = mergeLock.withLock { when {
            e.audioUrl != null -> MediaMerger.merge(videoPart, audioPart, videoOut).also { videoPart.delete(); audioPart.delete() }
            isHls -> try {
                // HLS arrives as MPEG-TS / fragmented MP4 pieces; package it as a regular MP4.
                MediaMerger.remux(videoPart, videoOut).also { videoPart.delete() }
            } catch (ex: Exception) {
                if (ex is CancellationException || e.extractAudio) throw ex
                moveFile(videoPart, if (isPrivate) File("$base.ts") else uniqueSibling(target, "ts"))
            }
            else -> moveFile(videoPart, videoOut)
        } }
        if (e.extractAudio) {
            // "Audio only" from a website video: keep just the sound track.
            val src = out
            try { out = mergeLock.withLock { MediaMerger.extractAudio(src, work) } } finally { if (src != out) src.delete() }
        }
        // Removed or paused while processing: don't drop an untracked file into the download folder.
        if (!currentCoroutineContext().isActive) { out.delete(); throw CancellationException("Stopped") }
        if (isPrivate) out = publishFile(out, target)
        android.util.Log.i(TAG, "Post-processing took ${System.currentTimeMillis() - started} ms")
        val mime = when (out.extension.lowercase()) {
            "webm" -> if (e.extractAudio || e.mimeType.startsWith("audio/")) "audio/webm" else "video/webm"
            "m4a" -> "audio/mp4"
            "ts" -> "video/mp2t"
            "mp4" -> if (e.mimeType.startsWith("audio/")) e.mimeType else "video/mp4"
            else -> e.mimeType
        }
        dao.get(e.id)?.let {
            dao.update(it.copy(filePath = out.absolutePath, mimeType = mime, type = if (e.extractAudio) ContentType.AUDIO else ContentType.fromMime(mime)))
        }
        return out
    }

    /** Moves a finished file from private storage to the download folder under [target]'s name (its own extension). */
    fun publishFile(src: File, target: File): File {
        val dest = if (src.extension == target.extension) target else uniqueSibling(target, src.extension)
        return moveFile(src, dest)
    }

    /** Rename, or copy + delete across file systems. */
    private fun moveFile(src: File, dest: File): File {
        if (dest.exists()) dest.delete()
        if (src.renameTo(dest)) return dest
        try {
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        } catch (e: IOException) {
            dest.delete()
            throw IOException("Could not save file: ${e.message}")
        }
        src.delete()
        return dest
    }

    private fun uniqueSibling(target: File, ext: String): File {
        val base = target.nameWithoutExtension
        var f = File(target.parentFile, "$base.$ext")
        var n = 1
        while (f.exists()) f = File(target.parentFile, "$base (${n++}).$ext")
        return f
    }

    /** Removes every partial and intermediate file of a task, wherever it was downloading. */
    fun deleteParts(e: DownloadEntity) {
        for (p in listOf("${e.filePath}.part", "${e.filePath}.audio.part")) {
            File(p).delete()
            File("$p.idx").delete()
            ChunkState.stateFile(File(p)).delete()
            InPlaceMp4Merge.marker(File(p)).delete()
        }
        partsDir().listFiles { f -> f.name.startsWith("task-${e.id}.") }?.forEach { it.delete() }
    }

    fun partsDir() = File(context.noBackupFilesDir, "parts").apply { mkdirs() }

    fun probeDurationSec(file: File): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1000) / 1000
        } finally { r.release() }
    }.getOrDefault(-1)

    companion object {
        private const val TAG = "DownloadEngine"
    }
}
