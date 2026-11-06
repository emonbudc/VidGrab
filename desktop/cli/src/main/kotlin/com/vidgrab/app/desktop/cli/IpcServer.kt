package com.vidgrab.app.desktop.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Serializable
private data class DownloadRequest(
    val url: String,
    val mediaUrl: String? = null,
    val audio: Boolean? = null,
    val quality: Int? = null,
    val title: String? = null,
)

@Serializable
private data class JobStatusResponse(
    val id: String,
    val state: String,
    val title: String? = null,
    val format: String? = null,
    val file: String? = null,
    val error: String? = null,
    val done: Long = -1,
    val total: Long = -1,
)

@Serializable
private data class OkResponse(
    val ok: Boolean = true,
    val id: String? = null,
    val version: String? = null,
)

@Serializable
private data class ErrorResponse(val ok: Boolean = false, val error: String)

private data class Job(
    val id: String,
    val state: String,
    val title: String? = null,
    val format: String? = null,
    val file: String? = null,
    val error: String? = null,
    val done: Long = -1,
    val total: Long = -1,
) {
    fun status() = JobStatusResponse(id, state, title, format, file, error, done, total)
}

/**
 * Loopback HTTP bridge for the browser extension.
 *
 * POST /download  {"url": "...", "mediaUrl": "...", "audio": false, "quality": 1080}
 * GET  /jobs/<id> job state, progress and final path
 * GET  /status    app reachability check
 *
 * Every request needs the shared token in X-VidGrab-Token (or ?token=), and the Host header must be
 * loopback: a random page can reach 127.0.0.1, but not without the token.
 */
internal class IpcServer(
    private val engine: MediaEngine,
    private val token: String,
    private val port: Int,
    private val outDir: File,
    private val tools: File?,
    private val defaultAudio: Boolean,
    private val defaultQuality: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val queue = Channel<Pair<String, DownloadRequest>>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
        server.createContext("/") { exchange -> route(exchange) }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        scope.launch { worker() }
        println("VidGrab listening on http://127.0.0.1:$port")
        println("token: $token")
        println("output: ${outDir.absolutePath}")
        try {
            awaitCancellation()
        } finally {
            server.stop(0)
            scope.cancel()
        }
    }

    private fun route(exchange: HttpExchange) {
        try {
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.set("Access-Control-Allow-Headers", "content-type, x-vidgrab-token")
            exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            if (exchange.requestMethod.equals("OPTIONS", true)) {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val host = exchange.requestHeaders.getFirst("Host")?.substringBefore(':')?.trim('[', ']')
            if (host !in setOf("127.0.0.1", "localhost", "::1", null)) {
                send(exchange, 403, errorJson("forbidden host"))
                return
            }
            if (!tokenMatches(exchange)) {
                send(exchange, 401, errorJson("bad token"))
                return
            }
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "GET" && path == "/status" ->
                    send(exchange, 200, json.encodeToString(OkResponse(version = VERSION)))
                exchange.requestMethod == "POST" && path == "/download" -> handleDownload(exchange)
                exchange.requestMethod == "GET" && path.startsWith("/jobs/") ->
                    handleJob(exchange, path.removePrefix("/jobs/"))
                else -> send(exchange, 404, errorJson("not found"))
            }
        } catch (e: Exception) {
            runCatching { send(exchange, 500, errorJson(e.message ?: "internal error")) }
        } finally {
            exchange.close()
        }
    }

    private fun tokenMatches(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("X-VidGrab-Token")
        if (header == token) return true
        return exchange.requestURI.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=') == token
    }

    private fun handleDownload(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        val request = runCatching { json.decodeFromString<DownloadRequest>(body) }.getOrNull()
        if (request == null || request.url.isBlank()) {
            send(exchange, 400, errorJson("url is required"))
            return
        }
        val id = UUID.randomUUID().toString().take(8)
        jobs[id] = Job(id, "queued")
        queue.trySend(id to request)
        send(exchange, 202, json.encodeToString(OkResponse(id = id)))
        println("queued $id: ${request.url}")
    }

    private fun handleJob(exchange: HttpExchange, id: String) {
        val job = jobs[id]
        if (job == null) send(exchange, 404, errorJson("unknown job")) else send(exchange, 200, json.encodeToString(job.status()))
    }

    private suspend fun worker() {
        for ((id, request) in queue) {
            val queued = jobs[id] ?: continue
            jobs[id] = queued.copy(state = "resolving", title = request.title)
            try {
                val resolved = engine.resolve(request.url, request.mediaUrl)
                val option = engine.choose(
                    resolved.options,
                    request.audio ?: defaultAudio,
                    request.quality ?: defaultQuality,
                ) ?: error("no downloadable format found")
                val format = describe(option)
                jobs[id] = jobs[id]!!.copy(state = "downloading", title = resolved.title, format = format)
                println("downloading ${resolved.title} ($format)")

                var lastUpdate = 0L
                val file = engine.download(option, resolved.title, outDir, resolved.headers, tools) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 500) {
                        lastUpdate = now
                        jobs[id] = (jobs[id] ?: queued).copy(done = done, total = total)
                    }
                }
                val size = file.length()
                jobs[id] = jobs[id]!!.copy(state = "done", file = file.absolutePath, done = size, total = size)
                println("saved ${file.absolutePath}")
            } catch (e: Exception) {
                jobs[id] = jobs[id]?.copy(state = "failed", error = e.message ?: e.javaClass.simpleName)
                    ?: Job(id, "failed", error = e.message)
                System.err.println("job $id failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun send(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun errorJson(message: String) = json.encodeToString(ErrorResponse(error = message))
}
