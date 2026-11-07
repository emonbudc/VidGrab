package com.vidgrab.app.core.download

import com.vidgrab.app.core.extractor.HlsClient
import com.vidgrab.app.core.extractor.HlsPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP layer of the download engine: ranged chunks over several connections, a sequential fallback for
 * servers without range support, and HLS playlists.
 */
internal class TransferClient(
    client: OkHttpClient,
    private val hls: HlsClient,
    limit: suspend () -> Long,
) {
    // HTTP/1.1 only: each ranged chunk gets its own TCP connection instead of sharing one HTTP/2 connection,
    // which is what makes parallel chunks faster on CDNs that cap per-connection throughput.
    val http = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /*
     * App-wide limits, however many downloads are queued or running:
     * - at most TRANSFERS network transfers (chunks / HLS segments) in flight, shared by all running tasks;
     * - their own small thread pool, so the database, merging and the UI never wait behind blocking sockets.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val netDispatcher = Dispatchers.IO.limitedParallelism(TRANSFERS + 4)
    private val transfers = Semaphore(TRANSFERS)
    private val limiter = RateLimiter(limit)

    private data class Probe(val total: Long, val ranges: Boolean)

    /** Exact size and whether the server takes byte ranges, from a one-byte request. */
    private fun probe(url: String, headers: Map<String, String>): Probe = try {
        http.newCall(request(url, headers, "bytes=0-0")).execute().use { r ->
            when {
                r.code == 416 -> Probe(-1, false)
                !r.isSuccessful -> throw HttpStatusException(r.code)
                else -> {
                    val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (r.code == 206 && total != null && total > 0) Probe(total, true) else Probe(r.body.contentLength(), false)
                }
            }
        }
    } catch (e: java.net.ProtocolException) {
        // A server that mishandles the one-byte range: download it front to back instead.
        Probe(-1, false)
    }

    fun request(url: String, headers: Map<String, String>, range: String?): Request = Request.Builder().url(url)
        .header("User-Agent", headers["User-Agent"] ?: UA)
        .apply {
            range?.let { header("Range", it) }
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true) && !k.equals("Range", true)) header(k, v) }
        }
        .build()

    /**
     * Downloads a plain HTTP stream into [part]. With a known size and range support it is split into
     * [SegmentPlan.CHUNK] pieces fetched over several connections and written in place; completed pieces
     * are remembered so a pause or a crash resumes without losing them.
     */
    suspend fun fetch(url: String, isAudio: Boolean, part: File, headers: Map<String, String>, p: TaskProgress) {
        val probe = probe(url, headers)
        val counter = p.counter(isAudio)
        if (probe.total <= 0 || !probe.ranges) {
            ChunkState.stateFile(part).delete()
            fetchSequential(url, part, headers, probe, counter, p.totalOf(isAudio), p.meter)
            return
        }
        val total = probe.total
        p.totalOf(isAudio).set(total)
        val state = ChunkState.load(part, total)
        counter.set(state.doneBytes())
        val queue = ConcurrentLinkedQueue(state.pending())
        if (queue.isEmpty() && part.length() == total) return
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
            val channel = raf.channel
            coroutineScope {
                repeat(minOf(SegmentPlan.connectionsFor(total), queue.size)) {
                    launch(netDispatcher) {
                        while (true) {
                            val i = queue.poll() ?: break
                            transfers.withPermit { fetchChunk(url, headers, SegmentPlan.range(i, total), channel, counter, p.meter) }
                            state.markDone(i)
                        }
                    }
                }
            }
        }
    }

    /** One byte range, resumed inside the range on a dropped connection and retried with backoff. */
    private suspend fun fetchChunk(url: String, headers: Map<String, String>, range: LongRange, channel: FileChannel, counter: AtomicLong, meter: SpeedMeter) {
        var pos = range.first
        var attempt = 0
        val buf = ByteArray(128 * 1024)
        while (pos <= range.last) {
            currentCoroutineContext().ensureActive()
            try {
                http.newCall(request(url, headers, "bytes=$pos-${range.last}")).execute().use { resp ->
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                    if (resp.code != 206) throw IOException("The server ignored the byte range")
                    val input = resp.body.byteStream()
                    while (pos <= range.last) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), range.last - pos + 1).toInt())
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        var at = pos
                        while (bb.hasRemaining()) at += channel.write(bb, at)
                        pos += n
                        counter.addAndGet(n.toLong())
                        meter.add(n)
                        limiter.consume(n)
                        attempt = 0
                    }
                }
                if (pos <= range.last) throw IOException("Connection closed early")
            } catch (ex: IOException) {
                val fatal = ex is HttpStatusException && ex.code in 400..499 && ex.code != 408 && ex.code != 429
                if (fatal || ++attempt > 5) throw ex
                delay(600L * attempt)
            }
        }
    }

    /** Front-to-back download for servers without range support or without a known size. */
    private suspend fun fetchSequential(url: String, part: File, headers: Map<String, String>, probe: Probe, counter: AtomicLong, totalRef: AtomicLong, meter: SpeedMeter) {
        var done = if (probe.ranges && part.exists()) part.length() else 0L
        var total = probe.total
        counter.set(done)
        if (total > 0) totalRef.set(total)
        if (total > 0 && done >= total) return
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(done)
            raf.seek(done)
            http.newCall(request(url, headers, if (done > 0) "bytes=$done-" else null)).execute().use { resp ->
                if (resp.code == 416) return
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                if (resp.code == 200 && done > 0) {
                    // Server ignored Range: restart from scratch.
                    raf.setLength(0); raf.seek(0); done = 0; counter.set(0)
                }
                resp.body.contentLength().takeIf { it > 0 }?.let { total = done + it; totalRef.set(total) }
                val input = resp.body.byteStream()
                val buf = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    done += n
                    counter.addAndGet(n.toLong())
                    meter.add(n)
                    limiter.consume(n)
                }
            }
        }
        if (total > 0 && done < total) throw IOException("Connection closed early")
        totalRef.set(maxOf(total, done))
    }

    /**
     * Downloads every segment of an HLS media playlist into [part], in order, [HLS_PARALLEL] at a time.
     * Progress ("segments done" + byte offset) is kept in [part].idx so pauses resume mid-stream.
     */
    suspend fun fetchHls(url: String, isAudio: Boolean, part: File, headers: Map<String, String>, p: TaskProgress) {
        val playlist = hls.loadMedia(url, headers)
        if (!playlist.endList) throw IOException("Live streams can't be downloaded")
        val segments = playlist.segments
        if (segments.isEmpty()) throw IOException("Empty playlist")
        val idx = File(part.path + ".idx")
        var done = 0
        var offset = 0L
        idx.takeIf { it.exists() && part.exists() }?.readText()?.split(' ')?.let { v ->
            done = v.getOrNull(0)?.toIntOrNull() ?: 0
            offset = v.getOrNull(1)?.toLongOrNull() ?: 0
        }
        if (done == 0) { part.delete(); offset = 0 }
        val counter = p.counter(isAudio)
        val totalRef = p.totalOf(isAudio)
        counter.set(offset)
        val keys = ConcurrentHashMap<String, ByteArray>()
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(offset)
            raf.seek(offset)
            val init = playlist.init
            if (done == 0 && init != null) {
                val bytes = fetchSegment(init, headers, keys)
                raf.write(bytes); offset += bytes.size
            }
            while (done < segments.size) {
                currentCoroutineContext().ensureActive()
                val batch = segments.subList(done, minOf(done + HLS_PARALLEL, segments.size))
                val results = coroutineScope {
                    batch.map { seg -> async(netDispatcher) { transfers.withPermit { fetchSegment(seg, headers, keys) }.also { meter(p, it.size) } } }.awaitAll()
                }
                for (bytes in results) {
                    raf.write(bytes)
                    offset += bytes.size
                    done++
                }
                idx.writeText("$done $offset")
                counter.set(offset)
                totalRef.set(offset * segments.size / done)
            }
        }
        idx.delete()
        counter.set(offset)
        totalRef.set(offset)
    }

    private suspend fun meter(p: TaskProgress, n: Int) {
        p.meter.add(n)
        limiter.consume(n)
    }

    private suspend fun fetchSegment(seg: HlsPlaylist.Segment, headers: Map<String, String>, keys: MutableMap<String, ByteArray>): ByteArray =
        withContext(netDispatcher) {
            var attempt = 0
            while (true) {
                try {
                    val raw = http.newCall(request(seg.url, headers, seg.byteRange?.let { "bytes=${it.first}-${it.last}" })).execute().use { r ->
                        if (!r.isSuccessful) throw HttpStatusException(r.code)
                        r.body.bytes()
                    }
                    val key = seg.key ?: return@withContext raw
                    val keyUrl = key.url
                    if (key.method != "AES-128" || keyUrl == null) throw IOException("This video is DRM-protected and can't be downloaded")
                    val keyBytes = keys.getOrPut(keyUrl) {
                        http.newCall(request(keyUrl, headers, null)).execute().use { r ->
                            if (!r.isSuccessful) throw HttpStatusException(r.code)
                            r.body.bytes()
                        }
                    }
                    val iv = key.iv ?: ByteArray(16).also { b -> var n = seg.sequence; for (i in 15 downTo 8) { b[i] = (n and 0xff).toByte(); n = n shr 8 } }
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                    return@withContext cipher.doFinal(raw)
                } catch (ex: IOException) {
                    val fatal = ex is HttpStatusException && ex.code in 400..499 && ex.code != 408 && ex.code != 429
                    if (fatal || ++attempt >= 4) throw ex
                    delay(800L * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }

    /** Keeps total throughput of all downloads under the configured limit (0 = unlimited). Shared by every connection. */
    private class RateLimiter(private val limit: suspend () -> Long) {
        private val lock = Any()
        private var windowStart = System.currentTimeMillis()
        private var bytes = 0L
        @Volatile private var cached = 0L
        @Volatile private var cachedAt = 0L

        suspend fun consume(n: Int) {
            val now = System.currentTimeMillis()
            if (now - cachedAt > 1000) { cachedAt = now; cached = limit() }
            val l = cached
            if (l <= 0) return
            val wait = synchronized(lock) {
                bytes += n
                val elapsed = System.currentTimeMillis() - windowStart
                val expected = bytes * 1000 / l
                if (elapsed > 1000 && expected <= elapsed) { windowStart = System.currentTimeMillis(); bytes = 0 }
                expected - elapsed
            }
            if (wait > 0) delay(wait)
        }
    }

    companion object {
        /** Segments per HLS stream fetched together (held in memory until written in order). */
        private const val HLS_PARALLEL = 6
        /** Network transfers in flight across all downloads. */
        private const val TRANSFERS = 12
        private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
