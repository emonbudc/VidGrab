package com.vidgrab.app.core.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.vidgrab.app.core.common.Permissions
import com.vidgrab.app.core.database.DeletedHistoryDao
import com.vidgrab.app.core.database.DeletedHistoryEntity
import com.vidgrab.app.core.database.DownloadDao
import com.vidgrab.app.core.database.asEntity
import com.vidgrab.app.core.database.asModel
import com.vidgrab.app.core.model.DownloadStatus
import com.vidgrab.app.core.model.sidecarSubtitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal class LibraryRepositoryImpl(
    private val context: Context,
    private val dao: DownloadDao,
    private val history: DeletedHistoryDao,
    private val settings: SettingsRepository,
) : LibraryRepository {
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Shared: every screen watching the library would otherwise run its own Room query,
    // and download progress invalidates the table twice a second.
    override val downloads = dao.observeAll().map { list -> list.map { it.asModel() } }
        .stateIn(cacheScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override val trash = dao.observeTrash().map { list -> list.map { it.asModel() } }
        .stateIn(cacheScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override fun observe(id: Long) = dao.observe(id).map { it?.asModel() }
    override suspend fun get(id: Long) = dao.get(id)?.asModel()

    override suspend fun moveToTrash(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.markDeleted(ids, System.currentTimeMillis())
    }

    override suspend fun restore(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.clearDeleted(ids)
    }

    override val deletedHistory = history.observe().map { list -> list.map { it.asModel() } }
    override suspend fun removeDeletedHistory(ids: List<Long>) = history.delete(ids)
    override suspend fun clearDeletedHistory() = history.clear()

    override suspend fun deleteForever(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val entities = dao.byIds(ids)
        // Remember what was deleted so it can be downloaded again later.
        history.insertAll(entities.filter { it.status == DownloadStatus.COMPLETED }.map { it.toHistory(now) })
        entities.forEach { e ->
            val file = File(e.filePath)
            sidecarFiles(file).forEach { it.delete() }
            e.subtitlePath?.let { File(it).delete() }
            file.delete()
            File(e.filePath + ".part").delete()
            File(e.filePath + ".audio.part").delete()
            File(e.filePath + ".part.idx").delete()
            File(e.filePath + ".audio.part.idx").delete()
        }
        dao.delete(ids)
    }

    override suspend fun archiveMissing(): Int = withContext(Dispatchers.IO) {
        val gone = dao.byStatus(listOf(DownloadStatus.COMPLETED))
            .filter { !it.inVault && !File(it.filePath).exists() }
        if (gone.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        history.insertAll(gone.map { it.toHistory(now) })
        dao.delete(gone.map { it.id })
        gone.size
    }

    private fun com.vidgrab.app.core.database.DownloadEntity.toHistory(now: Long) = DeletedHistoryEntity(
        title = title, pageUrl = pageUrl, url = url, thumbnail = thumbnail, mimeType = mimeType, quality = quality,
        sizeBytes = totalBytes, durationSec = durationSec, deletedAt = now,
    )

    override suspend fun purgeExpiredTrash() {
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRASH_DAYS)
        deleteForever(dao.trashOlderThan(before).map { it.id })
    }

    override suspend fun setVault(ids: List<Long>, inVault: Boolean) = withContext(Dispatchers.IO) {
        val destDir = if (inVault) vaultDir() else publicDir()
        ids.forEach { id ->
            val e = dao.get(id) ?: return@forEach
            if (e.inVault == inVault) return@forEach
            val from = File(e.filePath)
            if (!inVault) {
                if (!from.exists()) {
                    dao.update(e.copy(inVault = false))
                    return@forEach
                }
                val to = uniqueTarget(destDir, from.name)
                if (moveFile(from, to)) {
                    val subtitlePath = moveSidecars(from, to, e.subtitlePath)
                    dao.update(e.copy(inVault = false, filePath = to.absolutePath, subtitlePath = subtitlePath))
                    MediaScannerConnection.scanFile(context, arrayOf(to.absolutePath), arrayOf(e.mimeType), null)
                }
                return@forEach
            }
            // Only finished files can be hidden: a running task would keep writing to a public path.
            if (e.status != DownloadStatus.COMPLETED || !from.exists()) return@forEach
            val to = uniqueTarget(destDir, from.name)
            if (!moveFile(from, to)) return@forEach
            val subtitlePath = moveSidecars(from, to, e.subtitlePath)
            dao.update(e.copy(inVault = true, filePath = to.absolutePath, subtitlePath = subtitlePath))
            // Drops the old public entry from MediaStore so other galleries stop listing it.
            MediaScannerConnection.scanFile(context, arrayOf(from.absolutePath), arrayOf(e.mimeType), null)
        }
    }

    override suspend fun migrateVault() = withContext(Dispatchers.IO) {
        val dir = vaultDir()
        dao.byVault().forEach { e ->
            if (e.status != DownloadStatus.COMPLETED) return@forEach
            val from = File(e.filePath)
            if (!from.exists() || from.parentFile?.absolutePath == dir.absolutePath) return@forEach
            val to = uniqueTarget(dir, from.name)
            if (!moveFile(from, to)) return@forEach
            val subtitlePath = moveSidecars(from, to, e.subtitlePath)
            dao.update(e.copy(filePath = to.absolutePath, subtitlePath = subtitlePath))
            MediaScannerConnection.scanFile(context, arrayOf(from.absolutePath), arrayOf(e.mimeType), null)
        }
    }

    private fun vaultDir() = File(context.noBackupFilesDir, "vault").apply { mkdirs() }

    private suspend fun publicDir(): File {
        val configured = File(settings.current().downloadDir).apply { mkdirs() }
        if (Permissions.allFilesAccess() && configured.canWrite()) return configured
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "VidGrab").apply { mkdirs() }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            f = File(dir, if (ext.isEmpty()) "$base (${n++})" else "$base (${n++}).$ext")
        }
        return f
    }

    private fun sidecarFiles(video: File): List<File> {
        val parent = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension
        return parent.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("$base.") && it.extension.lowercase() in SIDECAR_EXTENSIONS
        }
    }

    /** Moves captions beside the video (renaming their base) and returns the updated local subtitle path. */
    private fun moveSidecars(from: File, to: File, subtitlePath: String?): String? {
        val destDir = to.parentFile ?: return subtitlePath
        val newBase = to.nameWithoutExtension
        var updated = subtitlePath
        sidecarFiles(from).forEach { sidecar ->
            val suffix = sidecar.name.removePrefix(from.nameWithoutExtension)
            val dest = uniqueTarget(destDir, newBase + suffix)
            if (moveFile(sidecar, dest) && sidecar.absolutePath == subtitlePath) updated = dest.absolutePath
        }
        return updated
    }

    /** Rename, or copy + delete across file systems. */
    private fun moveFile(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.inputStream().use { input -> to.outputStream().use { output -> input.copyTo(output, 1 shl 20) } }
            from.delete()
        } catch (e: Exception) {
            to.delete()
            false
        }
    }

    override suspend fun rename(id: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext false
        val old = File(e.filePath)
        val ext = old.extension
        val safe = newTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { return@withContext false }
        val target = File(old.parentFile, if (ext.isBlank()) safe else "$safe.$ext")
        if (target.exists() && target != old) return@withContext false
        if (old.exists() && !old.renameTo(target)) return@withContext false
        val subtitlePath = moveSidecars(old, target, e.subtitlePath)
        dao.update(e.copy(title = safe, filePath = target.absolutePath, subtitlePath = subtitlePath))
        true
    }

    override suspend fun importFolder(dir: String): Int = withContext(Dispatchers.IO) {
        val root = File(dir)
        if (!root.isDirectory) return@withContext 0
        val known = dao.allPaths().toHashSet()
        val media = root.walkTopDown().maxDepth(2).filter { f ->
            f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".video.mp4") && f.extension.lowercase() in MEDIA_EXT && f.absolutePath !in known
        }.toList()
        media.forEach { f ->
            val ext = f.extension.lowercase()
            val mime = when (ext) {
                "m4a", "aac" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus", "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
                "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "ts" -> "video/mp2t"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                else -> "video/mp4"
            }
            // File names look like "Title(720p).mp4": split the quality back out.
            val base = f.nameWithoutExtension
            val q = Regex("""\(([^()]{1,12})\)(?: \(\d+\))?$""").find(base)?.groupValues?.get(1)
            val title = if (q != null) base.substringBeforeLast("($q)").trim() else base
            val duration = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1000) / 1000
                } finally { r.release() }
            }.getOrDefault(-1)
            val subtitle = if (mime.startsWith("video/")) sidecarSubtitles(f).firstOrNull() else null
            dao.insert(
                com.vidgrab.app.core.database.DownloadEntity(
                    title = title, pageUrl = null, sourceHost = null, thumbnail = null, url = Uri.fromFile(f).toString(), audioUrl = null,
                    filePath = f.absolutePath, mimeType = mime, type = com.vidgrab.app.core.model.ContentType.fromMime(mime), quality = q,
                    totalBytes = f.length(), downloadedBytes = f.length(), audioTotalBytes = -1, audioDownloadedBytes = 0,
                    status = com.vidgrab.app.core.model.DownloadStatus.COMPLETED, error = null, durationSec = duration,
                    createdAt = f.lastModified(), completedAt = f.lastModified(), inVault = false, deletedAt = 0, lastPlayedPositionMs = 0,
                    subtitlePath = subtitle?.url?.let { Uri.parse(it).path }, subtitleMime = subtitle?.mimeType,
                    subtitleLanguage = subtitle?.language, subtitleLabel = subtitle?.label, subtitleAuto = false,
                ),
            )
        }
        media.size
    }

    override suspend fun savePosition(id: Long, positionMs: Long) = dao.updatePosition(id, positionMs)

    override suspend fun addLocalFile(task: com.vidgrab.app.core.model.DownloadTask) =
        dao.insert(task.asEntity())

    companion object {
        private val MEDIA_EXT = setOf("mp4", "m4a", "mp3", "webm", "mkv", "mov", "3gp", "aac", "opus", "ogg", "wav", "ts", "jpg", "jpeg", "png")
        private val SIDECAR_EXTENSIONS = setOf("vtt", "srt", "ass", "ssa", "ttml")
        const val TRASH_DAYS = 30L
    }
}
