package com.vidgrab.app.desktop.cli

import com.vidgrab.app.core.extractor.FacebookReader
import com.vidgrab.app.core.extractor.HlsClient
import com.vidgrab.app.core.extractor.NewPipeMediaExtractor
import com.vidgrab.app.core.extractor.StreamResolver
import com.vidgrab.app.core.extractor.WebMediaFinder
import com.vidgrab.app.core.model.DownloadOption
import com.vidgrab.app.core.model.FormatKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.SearchFilter
import com.vidgrab.app.core.model.StreamProtocol
import okhttp3.OkHttpClient
import java.io.File

internal data class Resolved(
    val title: String,
    val options: List<DownloadOption>,
    val headers: Map<String, String>,
)

/** Shared extraction + download logic used by the CLI and the browser bridge. */
internal class MediaEngine(private val client: OkHttpClient) {
    private val extractor = NewPipeMediaExtractor(client)
    private val web = WebMediaFinder(client)
    private val resolver = StreamResolver(client, HlsClient(client))
    private val facebook = FacebookReader(client)

    suspend fun search(query: String): Page<MediaItem> = extractor.search(query, SearchFilter.ALL)

    /**
     * Resolves a page URL. When the page itself hides the stream, falls back to the direct media URL
     * supplied by the browser extension.
     */
    suspend fun resolve(url: String, mediaUrl: String? = null): Resolved {
        val first = runCatching { resolvePage(url) }
        first.getOrNull()?.takeIf { it.options.isNotEmpty() }?.let { return it }
        if (!mediaUrl.isNullOrBlank() && mediaUrl != url) {
            val direct = runCatching { resolvePage(mediaUrl) }
            direct.getOrNull()?.takeIf { it.options.isNotEmpty() }?.let { return it }
            if (first.isFailure) throw direct.exceptionOrNull() ?: first.exceptionOrNull()!!
        }
        return first.getOrNull() ?: throw first.exceptionOrNull()!!
    }

    private suspend fun resolvePage(url: String): Resolved {
        if (extractor.canHandle(url)) {
            val details = extractor.details(url)
            val options = details.options.ifEmpty {
                buildList {
                    details.playbackUrl?.let {
                        val mime = details.playbackMime ?: "video/mp4"
                        add(
                            DownloadOption(
                                id = "progressive",
                                kind = FormatKind.VIDEO,
                                title = "Video",
                                info = "",
                                ext = WebMediaFinder.extFor(mime),
                                mimeType = mime,
                                url = it,
                            )
                        )
                    }
                    details.hlsUrl?.let {
                        add(
                            DownloadOption(
                                id = "hls",
                                kind = FormatKind.VIDEO,
                                title = "HLS",
                                info = "",
                                ext = "mp4",
                                mimeType = "video/mp4",
                                url = it,
                                protocol = StreamProtocol.HLS,
                            )
                        )
                    }
                }
            }
            return Resolved(details.item.title, options, emptyMap())
        }

        if (FacebookReader.isFacebook(url)) {
            facebook.extract(url)?.let { return Resolved(it.title, it.options, it.headers) }
        }

        val page = web.find(url) ?: error("couldn't load that page or find media on it")
        val result = resolver.resolveWithReport(page.candidates, page.headers, page.durationSec)
        if (result.options.isEmpty()) {
            error("no downloadable media found (${result.report.take(3).joinToString("; ")})")
        }
        return Resolved(page.title.ifBlank { url }, result.options, page.headers)
    }

    fun choose(options: List<DownloadOption>, audio: Boolean, quality: Int): DownloadOption? {
        if (audio) {
            return options.filter { it.kind == FormatKind.AUDIO }.maxByOrNull { it.bitrate }
                ?: options.filter { it.extractAudio }.maxByOrNull { it.height }
        }
        val videos = options.filter { it.kind == FormatKind.VIDEO && !it.extractAudio }
        if (videos.isEmpty()) return options.maxByOrNull { it.height }
        if (quality > 0) {
            return videos.filter { it.height in 1..quality }.maxByOrNull { it.height }
                ?: videos.minByOrNull { it.height }
        }
        return videos.maxByOrNull { it.height }
    }

    suspend fun download(
        option: DownloadOption,
        title: String,
        outDir: File,
        headers: Map<String, String>,
        tools: File?,
        onProgress: (Long, Long) -> Unit,
    ): File = MediaDownloader(client, tools).download(option, title, outDir, headers, onProgress)
}
