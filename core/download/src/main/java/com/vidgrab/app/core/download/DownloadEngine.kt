package com.vidgrab.app.core.download

import android.content.Context
import android.media.MediaScannerConnection
import android.os.SystemClock
import com.vidgrab.app.core.common.Permissions
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.data.MediaRepository
import com.vidgrab.app.core.data.SettingsRepository
import com.vidgrab.app.core.database.DownloadDao
import com.vidgrab.app.core.database.DownloadEntity
import com.vidgrab.app.core.database.asModel
import com.vidgrab.app.core.extractor.HlsClient
import com.vidgrab.app.core.model.StreamProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.vidgrab.app.core.model.ContentType
import com.vidgrab.app.core.model.DownloadRequest
import com.vidgrab.app.core.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.vidgrab.app.core.extractor.UniversalExtractor
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * Queue + worker pool. Each task downloads its video and audio streams at once, each split into ranged
 * chunks over several connections (resumable, avoids per-connection throttling), then merges them.
 */
internal class DownloadEngine(
    private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val media: MediaRepository,
    private val network: NetworkMonitor,
    client: OkHttpClient,
    private val notifier: DownloadNotifier,
    hls: HlsClient,
    private val analytics: com.vidgrab.app.core.analytics.Analytics,
    /** Site extractor used to refresh expired or unsavable links of non-YouTube pages; null where unavailable. */
    private val universal: UniversalExtractor?,
) : DownloadManager {

    private val transfer = TransferClient(client, hls) { settings.current().speedLimitKbps * 1024L }
    private val post = DownloadPostProcessor(context, dao, notifier)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val scheduleLock = Mutex()
    private val _speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    override val speeds: StateFlow<Map<Long, Long>> = _speeds
    private val started = AtomicBoolean(false)
    private val lastPersist = ConcurrentHashMap<Long, Long>()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Anything "running" when the process died is resumed.
            dao.byStatus(listOf(DownloadStatus.RUNNING, DownloadStatus.MERGING)).forEach {
                dao.updateStatus(it.id, DownloadStatus.QUEUED)
            }
            reschedule()
        }
        scope.launch { network.type.drop(1).collect { reschedule() } }
        // Safety net: a task parked as "waiting for network" after a brief drop, or queued while all slots were
        // busy, would otherwise wait for the next network/settings event, which may never come.
        scope.launch {
            while (true) {
                delay(15_000)
                val waiting = dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK))
                if (waiting.any { !jobs.containsKey(it.id) }) reschedule()
            }
        }
        scope.launch {
            settings.settings.map { Triple(it.maxTasksWifi, it.maxTasksMobile, it.allowMobileData) }
                .distinctUntilChanged().drop(1).collect { reschedule() }
        }
    }

    /** Names are picked one at a time, so two downloads of the same title can't get the same file. */
    private val nameLock = Mutex()

    override suspend fun enqueue(request: DownloadRequest): Long = nameLock.withLock { enqueueLocked(request) }

    override fun submit(request: DownloadRequest) {
        scope.launch { enqueue(request) }
    }

    override suspend fun findExisting(request: DownloadRequest): com.vidgrab.app.core.model.DownloadTask? {
        val e = dao.findSame(request.pageUrl, request.title, Urls.host(request.pageUrl ?: request.url), request.quality) ?: return null
        return when (e.status) {
            DownloadStatus.COMPLETED -> e.takeIf { File(it.filePath).exists() }
            DownloadStatus.FAILED -> null
            else -> e // queued, running, paused, merging, waiting for network
        }?.asModel()
    }

    private suspend fun enqueueLocked(request: DownloadRequest): Long {
        val s = settings.current()
        // Public Downloads is the default; fall back to app storage if this device/OS version won't let us write there.
        val public = File(s.downloadDir).apply { mkdirs() }
        val dir = public.takeIf { Permissions.allFilesAccess() && it.canWrite() }
            ?: File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "VidGrab").apply { mkdirs() }
        val base = Urls.safeFileName(request.title)
        val suffix = request.quality?.let { "($it)" }.orEmpty()
        var file = File(dir, "$base$suffix.${request.ext}")
        var n = 1
        // Merged downloads only appear on disk when they finish, so names already given to tasks count too.
        while (file.exists() || File(file.path + ".part").exists() || dao.countWithPath(file.absolutePath) > 0) {
            file = File(dir, "$base$suffix ($n).${request.ext}"); n++
        }
        val headers = if (request.headers.isEmpty()) null
        else JsonObject(request.headers.mapValues { JsonPrimitive(it.value) }).toString()
        val id = dao.insert(
            DownloadEntity(
                title = request.title,
                pageUrl = request.pageUrl,
                sourceHost = Urls.host(request.pageUrl ?: request.url),
                thumbnail = request.thumbnail,
                url = request.url,
                audioUrl = request.audioUrl,
                filePath = file.absolutePath,
                mimeType = request.mimeType,
                type = ContentType.fromMime(request.mimeType),
                quality = request.quality,
                totalBytes = request.sizeBytes,
                downloadedBytes = 0,
                audioTotalBytes = if (request.audioUrl != null) request.audioSizeBytes else -1,
                audioDownloadedBytes = 0,
                status = DownloadStatus.QUEUED,
                error = null,
                durationSec = request.durationSec,
                createdAt = System.currentTimeMillis(),
                completedAt = 0,
                inVault = false,
                deletedAt = 0,
                lastPlayedPositionMs = 0,
                headers = headers,
                protocol = request.protocol,
                extractAudio = request.extractAudio,
                clipStartMs = request.clipStartMs,
                clipEndMs = request.clipEndMs,
            ),
        )
        reschedule()
        return id
    }

    override fun pause(id: Long) {
        jobs.remove(id)?.cancel()
        scope.launch {
            dao.updateStatus(id, DownloadStatus.PAUSED)
            reschedule()
        }
    }

    override fun resume(id: Long) {
        scope.launch {
            dao.updateStatus(id, DownloadStatus.QUEUED)
            reschedule()
        }
    }

    override fun pauseAll() {
        scope.launch {
            dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING_NETWORK)).forEach {
                jobs.remove(it.id)?.cancel()
                dao.updateStatus(it.id, DownloadStatus.PAUSED)
            }
            reschedule()
        }
    }

    override fun resumeAll() {
        scope.launch {
            dao.byStatus(listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)).forEach {
                dao.updateStatus(it.id, DownloadStatus.QUEUED)
            }
            reschedule()
        }
    }

    override suspend fun remove(ids: List<Long>) {
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            val e = dao.get(id) ?: return@forEach
            if (e.status != DownloadStatus.COMPLETED) {
                post.deleteParts(e)
                dao.delete(listOf(id))
            } else {
                dao.update(e.copy(deletedAt = System.currentTimeMillis()))
            }
        }
        reschedule()
    }

    override fun reschedule() {
        scope.launch {
            scheduleLock.withLock {
                val s = settings.current()
                val net = network.currentType()
                val active = dao.byStatus(listOf(DownloadStatus.RUNNING, DownloadStatus.MERGING))
                val queued = dao.byStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK))
                val blocked = net == NetworkType.NONE || (net == NetworkType.CELLULAR && !s.allowMobileData)
                if (blocked) {
                    queued.filter { it.status == DownloadStatus.QUEUED }
                        .forEach { dao.updateStatus(it.id, DownloadStatus.WAITING_NETWORK) }
                } else {
                    val max = if (net == NetworkType.WIFI) s.maxTasksWifi else s.maxTasksMobile
                    var slots = max - active.count { jobs.containsKey(it.id) }
                    for (task in queued) {
                        if (slots <= 0) {
                            if (task.status == DownloadStatus.WAITING_NETWORK) dao.updateStatus(task.id, DownloadStatus.QUEUED)
                            continue
                        }
                        if (jobs.containsKey(task.id)) continue
                        slots--
                        dao.updateStatus(task.id, DownloadStatus.RUNNING)
                        jobs[task.id] = scope.launch { runTask(task.id) }
                    }
                }
                notifier.refresh(hasWork = jobs.isNotEmpty() || queued.isNotEmpty() && !blocked)
            }
        }
    }

    private suspend fun runTask(id: Long) {
        var entity = dao.get(id) ?: return
        // Tasks saved by older versions may have names over Android's 255-byte limit, or share a name with another
        // task: give them a short, unique one before (re)trying.
        File(entity.filePath).let { f ->
            val shared = entity.status != DownloadStatus.COMPLETED && dao.countWithPath(entity.filePath) > 1
            if (f.name.toByteArray(Charsets.UTF_8).size > 180 || shared) {
                post.deleteParts(entity)
                val base = Urls.safeFileName(f.nameWithoutExtension)
                var short = File(f.parentFile, "$base.${f.extension}")
                var n = 1
                // Two old tasks of one video shorten to the same name: keep them apart.
                while (short.exists() || dao.countWithPath(short.absolutePath) > 0) short = File(f.parentFile, "$base (${n++}).${f.extension}")
                entity = entity.copy(filePath = short.absolutePath, downloadedBytes = 0, audioDownloadedBytes = 0)
                dao.update(entity)
            }
        }
        try {
            val target = File(entity.filePath)
            target.parentFile?.mkdirs()
            val saved = if (entity.clipEndMs > entity.clipStartMs && entity.protocol == StreamProtocol.HTTP) clip(id, target) else try {
                val base = partBase(entity)
                entity = download(id, File("$base.part"), File("$base.audio.part"))
                post.finish(entity, target, base)
            } catch (ex: UnsupportedCodecException) {
                // The site served a codec this phone can't store (AV1 before Android 14, VP9 with AAC…):
                // re-extract, take a compatible format of the same quality, download again.
                refreshUrls(dao.get(id) ?: return, compatibleOnly = true) ?: throw ex
                post.deleteParts(entity)
                val base = partBase(dao.get(id) ?: return)
                entity = download(id, File("$base.part"), File("$base.audio.part"))
                post.finish(entity, target, base)
            }
            val probedDuration = if (entity.durationSec > 0) entity.durationSec else post.probeDurationSec(saved)
            val done = (dao.get(id) ?: return).copy(
                durationSec = probedDuration,
                status = DownloadStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                totalBytes = saved.length(),
                downloadedBytes = saved.length(),
                audioTotalBytes = -1, audioDownloadedBytes = 0,
                error = null,
            )
            dao.update(done)
            post.deleteParts(entity)
            MediaScannerConnection.scanFile(context, arrayOf(saved.absolutePath), arrayOf(done.mimeType), null)
            notifier.completed(done.asModel())
            analytics.event(com.vidgrab.app.core.analytics.Events.DOWNLOAD_COMPLETED,
                mapOf("kind" to done.type.name.lowercase(), "protocol" to done.protocol.name.lowercase()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val net = network.currentType()
            val status = if (net == NetworkType.NONE) DownloadStatus.WAITING_NETWORK else DownloadStatus.FAILED
            dao.updateStatus(id, status, friendlyError(e))
            if (status == DownloadStatus.FAILED) {
                dao.get(id)?.let { notifier.failed(it) }
                // Only the error type, never the message (it can contain URLs).
                analytics.event(com.vidgrab.app.core.analytics.Events.DOWNLOAD_FAILED,
                    mapOf("error" to (if (e is HttpStatusException) "http_${e.code}" else e.javaClass.simpleName)))
            }
        } finally {
            jobs.remove(id)
            _speeds.update { it - id }
            lastPersist.remove(id)
            reschedule()
        }
    }

    /**
     * A cut of the video ([ClipDownload]): fetches only that part of the streams and writes the clip straight away.
     * Expired links are refreshed once; a format that can't be stored is swapped for a compatible one once.
     */
    private suspend fun clip(id: Long, target: File): File {
        var refreshed = false
        var swapped = false
        while (true) {
            val e = dao.get(id) ?: throw CancellationException("Removed")
            val audioOnly = e.mimeType.startsWith("audio/")
            val work = File(post.partsDir(), "task-$id.clip.${if (audioOnly) "m4a" else "mp4"}")
            val progress = TaskProgress(0, 0, e.totalBytes, e.audioTotalBytes)
            val headers = headersOf(e)
            val ticker = scope.launch { while (isActive) { delay(500); publish(id, progress) } }
            try {
                withContext(transfer.netDispatcher) {
                    ClipDownload(transfer.http, { url, range -> transfer.request(url, headers, range) }, e.clipStartMs * 1000, e.clipEndMs * 1000)
                        .run(e.url, e.audioUrl, audioOnly, work, progress.video, progress.audio) { n -> progress.meter.add(n) }
                }
                if (!currentCoroutineContext().isActive) { work.delete(); throw CancellationException("Stopped") }
                val out = post.publishFile(work, target)
                dao.get(id)?.let {
                    val mime = if (out.extension == "m4a") "audio/mp4" else "video/mp4"
                    dao.update(it.copy(filePath = out.absolutePath, mimeType = mime, type = ContentType.fromMime(mime)))
                }
                return out
            } catch (ex: HttpStatusException) {
                work.delete()
                if (ex.code != 403 && ex.code != 410 || refreshed) throw ex
                refreshed = true
                refreshUrls(e, compatibleOnly = false) ?: throw ex
            } catch (ex: UnsupportedCodecException) {
                work.delete()
                if (swapped) throw ex
                swapped = true
                refreshUrls(e, compatibleOnly = true) ?: throw ex
            } finally {
                ticker.cancel()
                publish(id, progress, force = true)
            }
        }
    }

    /** Fetches the video and (if separate) the audio stream at the same time, publishing progress twice a second. */
    private suspend fun download(id: Long, videoPart: File, audioPart: File): DownloadEntity = coroutineScope {
        val start = dao.get(id) ?: throw CancellationException("Removed")
        val progress = TaskProgress(start.downloadedBytes, start.audioDownloadedBytes.coerceAtLeast(0), start.totalBytes, start.audioTotalBytes)
        val refreshLock = Mutex()
        val ticker = launch { while (isActive) { delay(500); publish(id, progress) } }
        try {
            listOfNotNull(
                async { fetchWithRefresh(id, isAudio = false, videoPart, progress, refreshLock) },
                start.audioUrl?.let { async { fetchWithRefresh(id, isAudio = true, audioPart, progress, refreshLock) } },
            ).awaitAll()
            // A refresh can turn a single file into separate video + audio.
            if (start.audioUrl == null && dao.get(id)?.audioUrl != null) fetchWithRefresh(id, isAudio = true, audioPart, progress, refreshLock)
        } finally {
            ticker.cancel()
            publish(id, progress, force = true)
        }
        dao.get(id) ?: throw CancellationException("Removed")
    }

    private suspend fun publish(id: Long, p: TaskProgress, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = lastPersist[id] ?: 0L
        if (force || now - last >= PERSIST_INTERVAL_MS) {
            lastPersist[id] = now
            dao.updateTransfer(id, p.video.get(), p.audio.get(), p.videoTotal.get(), p.audioTotal.get())
        }
        _speeds.update { it + (id to p.meter.bytesPerSecond()) }
        notifier.refresh(true)
    }

    /**
     * Where a task's partial files live (without the ".part" suffix). Streams that get merged or remuxed are
     * downloaded to app-private storage: the muxer is ~3x slower on shared storage, which goes through FUSE.
     * Plain single files are written straight next to their final name. Parts from older versions stay put.
     */
    private fun partBase(e: DownloadEntity): String {
        val legacy = e.filePath
        if (File("$legacy.part").exists() || File("$legacy.audio.part").exists()) return legacy
        val private = File(post.partsDir(), "task-${e.id}").path
        if (File("$private.part").exists() || File("$private.audio.part").exists()) return private
        val needsProcessing = e.audioUrl != null || e.protocol == StreamProtocol.HLS || e.extractAudio
        if (!needsProcessing) return legacy
        // Room for the parts plus the merged copy.
        val need = (e.totalBytes.coerceAtLeast(0) + e.audioTotalBytes.coerceAtLeast(0)) * 2 + 64L * 1024 * 1024
        return if (post.partsDir().usableSpace > need) private else legacy
    }

    private fun friendlyError(e: Throwable) = when {
        e is HttpStatusException && (e.code == 401 || e.code == 403) -> "Link expired or login required"
        e is HttpStatusException && e.code == 404 -> "File not found"
        e is UnsupportedCodecException -> e.message
        e is java.net.UnknownHostException -> "No internet connection"
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e.message?.contains("ENOSPC") == true -> "Not enough storage"
        e is OutOfMemoryError -> "Not enough memory to process this video"
        else -> com.vidgrab.app.core.common.DownloadErrors.friendly(e.message)
    }

    private fun headersOf(e: DownloadEntity): Map<String, String> = e.headers?.let { h ->
        Json.parseToJsonElement(h).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.orEmpty()

    /**
     * Downloads one stream. When its link expired (403/410) on any site, the page is extracted again and the
     * download continues with fresh links. The two streams of a task share [refreshLock] so they refresh once.
     */
    private suspend fun fetchWithRefresh(id: Long, isAudio: Boolean, part: File, progress: TaskProgress, refreshLock: Mutex) {
        var refreshed = false
        while (true) {
            val e = dao.get(id) ?: return
            val url = (if (isAudio) e.audioUrl else e.url) ?: return
            try {
                if (e.protocol == StreamProtocol.HLS) transfer.fetchHls(url, isAudio, part, headersOf(e), progress)
                else transfer.fetch(url, isAudio, part, headersOf(e), progress)
                return
            } catch (ex: HttpStatusException) {
                if (ex.code != 403 && ex.code != 410 || refreshed) throw ex
                refreshed = true
                refreshLock.withLock {
                    val cur = dao.get(id) ?: throw ex
                    // The other stream may have refreshed already.
                    if ((if (isAudio) cur.audioUrl else cur.url) == url) refreshUrls(cur, compatibleOnly = false) ?: throw ex
                }
            }
        }
    }

    /**
     * Re-extracts the page and stores fresh links for the closest matching format. With [compatibleOnly] the
     * current format is skipped (it couldn't be saved) and the partial files are discarded.
     */
    private suspend fun refreshUrls(e: DownloadEntity, compatibleOnly: Boolean): DownloadEntity? {
        val page = e.pageUrl ?: return null
        val headers = headersOf(e)
        var pageHeaders = emptyMap<String, String>()
        val options = if (media.canExtract(page)) {
            runCatching { media.details(page, forceRefresh = true).options }.getOrNull()
        } else {
            universal?.takeIf { it.isReady }?.let { u ->
                runCatching { u.extract(page, headers) }.getOrNull()?.also { pageHeaders = it.headers }?.options
            }
        } ?: return null
        val height = e.quality?.substringBefore('p')?.trim()?.toIntOrNull()
        val sameKind = when {
            e.extractAudio -> options.filter { it.extractAudio }.ifEmpty { options.filter { it.mimeType.startsWith("video/") } }
            e.mimeType.startsWith("audio/") -> options.filter { it.mimeType.startsWith("audio/") && !it.extractAudio }
            else -> options.filter { it.mimeType.startsWith("video/") && !it.extractAudio }
        }
        // Fresh links always differ, so the format that couldn't be saved is recognised by what it is.
        val failed = { o: com.vidgrab.app.core.model.DownloadOption ->
            o.ext == e.filePath.substringAfterLast('.') && o.protocol == e.protocol && (o.audioUrl != null) == (e.audioUrl != null) &&
                (height == null || o.height == height)
        }
        val candidates = if (compatibleOnly) sameKind.filterNot(failed).ifEmpty { sameKind.filter { it.url != e.url && !failed(it) } } else sameKind
        // Options are listed worst to best, so a tie (e.g. sizes unknown) goes to the better one.
        val option = (if (height != null) candidates.withIndex().minByOrNull { (i, o) -> kotlin.math.abs(o.height - height) * 1000L - i }?.value else null)
            ?: candidates.firstOrNull { o -> e.quality?.let { q -> o.title.contains(q) } == true }
            ?: candidates.firstOrNull()
            ?: return null
        val mergedHeaders = (headers + pageHeaders).takeIf { it.isNotEmpty() }
            ?.let { h -> JsonObject(h.mapValues { JsonPrimitive(it.value) }).toString() }
        val cur = dao.get(e.id) ?: e
        val sameFormat = !compatibleOnly && option.protocol == cur.protocol && (option.audioUrl != null) == (cur.audioUrl != null)
        val updated = cur.copy(
            url = option.url, audioUrl = option.audioUrl, protocol = option.protocol, headers = mergedHeaders ?: cur.headers,
            quality = if (height != null && option.height > 0) "${option.height}p" else cur.quality,
            totalBytes = if (sameFormat) cur.totalBytes else option.sizeBytes,
            audioTotalBytes = if (option.audioUrl == null) -1 else if (sameFormat) cur.audioTotalBytes else option.audioSizeBytes,
            downloadedBytes = if (sameFormat) cur.downloadedBytes else 0,
            audioDownloadedBytes = if (sameFormat) cur.audioDownloadedBytes else 0,
        )
        if (!sameFormat) post.deleteParts(cur)
        dao.update(updated)
        return updated
    }

    companion object {
        /** Progress reaches the UI at 2 Hz; the database only needs it every couple of seconds. */
        private const val PERSIST_INTERVAL_MS = 2_000L
    }
}
