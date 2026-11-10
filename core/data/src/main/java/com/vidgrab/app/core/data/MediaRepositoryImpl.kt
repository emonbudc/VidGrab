package com.vidgrab.app.core.data

import android.util.LruCache
import com.vidgrab.app.core.common.BrowserSession
import com.vidgrab.app.core.extractor.ExtractedPage
import com.vidgrab.app.core.extractor.MediaExtractor
import com.vidgrab.app.core.extractor.UniversalExtractor
import com.vidgrab.app.core.model.FormatKind
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.SearchFilter
import com.vidgrab.app.core.model.StreamProtocol
import com.vidgrab.app.core.model.VideoDetails
import kotlinx.coroutines.CancellationException

internal class MediaRepositoryImpl(
    private val extractor: MediaExtractor,
    private val universal: UniversalExtractor? = null,
) : MediaRepository {
    // Stream URLs expire after a few hours; keep details briefly so back/forward navigation is instant.
    private val detailsCache = LruCache<String, Pair<Long, VideoDetails>>(30)

    override suspend fun suggestions(query: String) = extractor.suggestions(query)
    // YouTube sometimes lists the same video twice in one page; the lists key items by URL, so duplicates go here.
    override suspend fun search(query: String, filter: SearchFilter, page: Any?) = extractor.search(query, filter, page).distinct()
    override suspend fun discover(page: Any?) = extractor.discover(page).distinct()
    override suspend fun comments(url: String, page: Any?) = extractor.comments(url, page)
    override suspend fun channel(url: String, page: Any?) = extractor.channel(url, page).let { it.copy(videos = it.videos.distinct()) }
    override suspend fun playlist(url: String, page: Any?) = extractor.playlist(url, page).let { it.copy(videos = it.videos.distinct()) }
    override fun canExtract(url: String) = extractor.canHandle(url)
    override fun linkKind(url: String) = extractor.linkKind(url)

    override suspend fun details(url: String, forceRefresh: Boolean): VideoDetails {
        val now = System.currentTimeMillis()
        if (!forceRefresh) detailsCache.get(url)?.let { (at, d) -> if (now - at < CACHE_MS) return d }
        val details = try {
            extractor.details(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // NewPipe still refuses some signed-in videos (older clients, changed age gates). The bundled
            // yt-dlp handles YouTube login cookies better, so use it as a last resort for YouTube links.
            if (universal == null || !BrowserSession.isYouTubeHost(url)) throw error
            universal.extract(url, BrowserSession.headers(url))?.toVideoDetails(url) ?: throw error
        }
        return details.copy(related = details.related.distinctBy { i -> i.url })
            .also { detailsCache.put(url, now to it) }
    }

    private fun Page<MediaItem>.distinct() = copy(items = items.distinctBy { it.url })

    private fun ExtractedPage.toVideoDetails(url: String): VideoDetails {
        val videos = options.filter { it.kind == FormatKind.VIDEO }
        // Prefer muxed streams: the player only auto-merges video+audio at 360–720p.
        val playback = videos.firstOrNull { it.protocol == StreamProtocol.HTTP && it.audioUrl == null }
            ?: videos.firstOrNull { it.protocol == StreamProtocol.HTTP && it.height in 361..720 }
            ?: videos.firstOrNull { it.protocol == StreamProtocol.HTTP }
        return VideoDetails(
            item = MediaItem(
                url = url,
                title = title,
                thumbnail = thumbnail,
                durationSec = durationSec?.toLong() ?: -1,
                kind = ItemKind.VIDEO,
            ),
            description = "",
            tags = emptyList(),
            likeCount = -1,
            subscriberCount = -1,
            uploadDate = null,
            playbackUrl = playback?.url,
            playbackMime = null,
            hlsUrl = videos.firstOrNull { it.protocol == StreamProtocol.HLS }?.url,
            dashUrl = null,
            audioPlaybackUrl = playback?.audioUrl
                ?: options.firstOrNull { it.kind == FormatKind.AUDIO && it.protocol == StreamProtocol.HTTP }?.url,
            options = options,
            related = emptyList(),
            commentsUrl = null,
            subtitles = subtitles,
        )
    }

    private companion object {
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
