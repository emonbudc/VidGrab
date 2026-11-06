package com.vidgrab.app.desktop.cli

import com.vidgrab.app.core.model.DownloadOption
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

fun main(args: Array<String>): Unit = runBlocking {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help" || args[0] == "help") {
        printUsage()
        return@runBlocking
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    val engine = MediaEngine(client)
    try {
        val app = CliApp(engine)
        when (args[0]) {
            "search" -> app.search(args.drop(1))
            "info" -> app.info(args.drop(1))
            "download" -> app.download(args.drop(1))
            "serve" -> runServe(engine, args.drop(1))
            "version", "--version" -> println("VidGrab CLI $VERSION")
            else -> {
                System.err.println("unknown command: ${args[0]}")
                printUsage()
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        System.err.println("error: ${e.message ?: e.javaClass.simpleName}")
        exitProcess(1)
    }
}

private fun printUsage() {
    println(
        """
        VidGrab CLI $VERSION
        Desktop prototype: NewPipe extractor + web media resolver + downloader + browser bridge.

        usage:
          vidgrab-cli search <query>
          vidgrab-cli info <url>
          vidgrab-cli download <url> [options]
          vidgrab-cli serve [options]

        download options:
          -a, --audio            pick the best audio-only stream
          -q, --quality <height> cap video height (e.g. 1080)
          -o, --out <dir>        output directory (default: current)
              --tools <dir>      directory containing the ffmpeg binary

        serve options:
              --port <port>      loopback port for the browser extension (default 37821)
              --token <token>    shared token; generated when omitted
              --out <dir>        output directory (default: ~/Downloads/VidGrab)
              --tools <dir>      directory containing the ffmpeg binary
          -a, --audio            default to audio-only downloads
          -q, --quality <height> default video height cap

        ffmpeg is required for muxing separated audio/video tracks and for HLS.
        """.trimIndent()
    )
}

private suspend fun runServe(engine: MediaEngine, args: List<String>) {
    var port = 37821
    var token = System.getenv("VIDGRAB_TOKEN").orEmpty()
    var outDir = File(System.getProperty("user.home"), "Downloads/VidGrab")
    var tools: File? = null
    var audio = false
    var quality = 0
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--port" -> port = args.getOrNull(++index)?.toIntOrNull() ?: error("--port needs a number")
            "--token" -> token = args.getOrNull(++index) ?: error("--token needs a value")
            "--out" -> outDir = File(args.getOrNull(++index) ?: error("--out needs a path"))
            "--tools" -> tools = File(args.getOrNull(++index) ?: error("--tools needs a path"))
            "-a", "--audio" -> audio = true
            "-q", "--quality" -> quality = args.getOrNull(++index)?.toIntOrNull() ?: error("--quality needs a number")
            else -> error("unknown flag: $arg")
        }
        index++
    }
    if (token.isBlank()) token = UUID.randomUUID().toString().replace("-", "").take(24)
    IpcServer(engine, token, port, outDir, tools, audio, quality).run()
}

private class CliApp(private val engine: MediaEngine) {

    suspend fun search(args: List<String>) {
        val query = args.joinToString(" ").trim()
        if (query.isEmpty()) error("usage: search <query>")
        val page = engine.search(query)
        if (page.items.isEmpty()) {
            println("no results")
            return
        }
        page.items.forEach { item ->
            val meta = buildList {
                if (item.durationSec > 0) add(humanDuration(item.durationSec))
                if (item.uploader.isNotBlank()) add(item.uploader)
                if (item.viewCount >= 0) add("${humanCount(item.viewCount)} views")
            }.joinToString(" · ")
            println(item.title)
            println("  ${item.url}${if (meta.isNotEmpty()) "  ($meta)" else ""}")
        }
    }

    suspend fun info(args: List<String>) {
        val url = args.firstOrNull() ?: error("usage: info <url>")
        val resolved = engine.resolve(url)
        println(resolved.title)
        if (resolved.options.isEmpty()) {
            println("  no downloadable formats exposed")
            return
        }
        resolved.options.forEachIndexed { index, option ->
            println("  [${index + 1}] ${describe(option)}")
        }
    }

    suspend fun download(args: List<String>) {
        var url: String? = null
        var audio = false
        var quality = 0
        var outDir = File(System.getProperty("user.dir"))
        var tools: File? = null
        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "-a", "--audio" -> audio = true
                "-q", "--quality" -> quality = args.getOrNull(++index)?.toIntOrNull()
                    ?: error("--quality needs a number, e.g. 1080")
                "-o", "--out" -> outDir = File(args.getOrNull(++index) ?: error("--out needs a path"))
                "--tools" -> tools = File(args.getOrNull(++index) ?: error("--tools needs a path"))
                else -> {
                    if (arg.startsWith("-")) error("unknown flag: $arg") else url = arg
                }
            }
            index++
        }
        val targetUrl = url ?: error("usage: download <url> [--audio] [--quality 1080] [--out dir] [--tools dir]")
        val resolved = engine.resolve(targetUrl)
        val option = engine.choose(resolved.options, audio, quality) ?: error("no downloadable format found")

        println(resolved.title)
        println("  ${describe(option)}")
        if (option.audioUrl != null) println("  video and audio are separate tracks; merging with ffmpeg")

        var lastUpdate = 0L
        val file = engine.download(option, resolved.title, outDir, resolved.headers, tools) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 200) {
                lastUpdate = now
                printProgress(done, total)
            }
        }
        println()
        println("saved: ${file.absolutePath}")
    }
}

internal fun describe(option: DownloadOption): String {
    val quality = when {
        option.height > 0 -> "${option.height}p"
        option.bitrate > 0 -> "${option.bitrate} kbps"
        else -> option.title
    }
    val size = if (option.totalSize > 0) " · ${humanBytes(option.totalSize)}" else ""
    val merge = if (option.audioUrl != null) " · needs merge" else ""
    val audioOnly = if (option.extractAudio) " · audio only" else ""
    return "$quality · ${option.ext} · ${option.mimeType}$size$merge$audioOnly"
}

internal fun humanBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
}

internal fun humanDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

private fun humanCount(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1fB".format(Locale.ROOT, value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1fM".format(Locale.ROOT, value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(Locale.ROOT, value / 1_000.0)
    else -> value.toString()
}

private fun printProgress(done: Long, total: Long) {
    val percent = if (total > 0) "%.1f%%".format(Locale.ROOT, done * 100.0 / total) else humanBytes(done)
    val size = if (total > 0) "${humanBytes(done)} / ${humanBytes(total)}" else humanBytes(done)
    print("\r  $percent  $size      ")
    System.out.flush()
}
