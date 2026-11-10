package com.vidgrab.app.core.data

import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.extractor.AccountFeed
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.SearchFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** The search shape of a Home tab. [keywords] help match searches and watched titles to the tab. */
data class RecommendationTopic(
    val query: String,
    val filter: SearchFilter,
    val keywords: List<String> = emptyList(),
)

/** A home feed built from what was searched for and what was watched lately. */
interface RecommendationRepository {
    /**
     * Returns one page; [page] is the cursor from the previous page. With a [topic] the feed stays inside that tab:
     * the topic query is always searched, matching history is used directly and other recent searches are narrowed
     * with the topic query.
     */
    suspend fun feed(
        page: Any? = null,
        topic: RecommendationTopic? = null,
        exclude: Set<String> = emptySet(),
    ): Page<MediaItem>
}

/**
 * YouTube-style home recommendations without a server profile: recent watched videos become related-video seeds,
 * recent searches become search seeds, and the results are ranked by seed strength, result position, views, freshness
 * and how often a channel appears. Videos already opened are dropped so the feed doesn't repeat itself.
 */
internal class RecommendationRepositoryImpl(
    private val media: MediaRepository,
    private val searches: SearchHistoryRepository,
    private val playback: PlaybackHistoryRepository,
    private val accountFeed: AccountFeed? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : RecommendationRepository {

    private class Source(
        val query: String? = null,
        val videoUrl: String? = null,
        val filter: SearchFilter = SearchFilter.VIDEOS,
        val weight: Double,
        var next: Any? = null,
        var started: Boolean = false,
        var exhausted: Boolean = false,
    )

    private data class Scored(val item: MediaItem, val base: Double)

    private data class Cursor(
        val sources: List<Source>,
        val topic: RecommendationTopic? = null,
        val pool: MutableList<Scored> = mutableListOf(),
        val seen: MutableSet<String> = mutableSetOf(),
    )

    private data class FallbackCursor(
        val next: Any,
        val topic: RecommendationTopic? = null,
        val exclude: Set<String> = emptySet(),
    )

    override suspend fun feed(page: Any?, topic: RecommendationTopic?, exclude: Set<String>): Page<MediaItem> {
        val feed = accountFeed
        if (topic == null && feed != null && (page == null || feed.owns(page))) {
            if (feed.isAvailable) {
                val accountPage = runCatching { feed.home(page) }.getOrNull()
                if (accountPage != null &&
                    (accountPage.items.isNotEmpty() || page != null || accountPage.nextPage != null)
                ) {
                    return accountPage.copy(items = accountPage.items.filterNot { it.url in exclude })
                }
            }
            if (page != null) return Page(emptyList(), null)
        }
        if (page is FallbackCursor) return fallback(page.next, page.topic, page.exclude)
        val cursor = (page as? Cursor) ?: newCursor(topic, exclude)
        if (cursor.sources.isEmpty()) return fallback(null, topic, exclude)

        // A page can be thinned out by the short/duplicate filters; top it up from the next search pages.
        var rounds = 0
        while (cursor.pool.size < PAGE_SIZE && rounds++ < 3) {
            if (cursor.sources.none { !it.exhausted && it.started }) break
            val before = cursor.pool.size
            fetchAll(cursor, onlyNext = true)
            if (cursor.pool.size == before) break
        }
        if (cursor.pool.isEmpty() && cursor.sources.all { it.exhausted }) return fallback(null, cursor.topic, cursor.seen)
        return buildPage(cursor)
    }

    private suspend fun newCursor(topic: RecommendationTopic?, exclude: Set<String>): Cursor {
        val recentQueries = searches.recent.first()
            .map { it.trim() }
            .filter { it.isNotBlank() && !Urls.looksLikeUrl(it) }
            .distinct()
        val relevantQueries = recentQueries
            .filter { topic == null || topic.matches(it) }
            .take(if (topic == null) MAX_SEARCH_SEEDS else MAX_TOPIC_SEARCH_SEEDS)
        val broadQueries = if (topic == null) {
            emptyList()
        } else {
            recentQueries.filterNot { topic.matches(it) }.take(MAX_BROAD_SEEDS)
        }
        val history = playback.all().sortedByDescending { it.openedAt }
        val watched = history.mapNotNull { entry ->
            val url = entry.key.removePrefix("web:")
            if (entry.key.startsWith("web:") && Urls.youtubeVideoId(url) != null) entry to url else null
        }
        val topicalWatched = watched.filter { (entry, _) -> topic == null || topic.matches(entry.title) }
        if (topic == null && relevantQueries.isEmpty() && topicalWatched.isEmpty()) return Cursor(emptyList())

        val cursor = Cursor(
            sources = buildList<Source> {
                topic?.let { add(Source(query = it.query, filter = it.filter, weight = TOPIC_SEED_WEIGHT)) }
                topicalWatched.take(MAX_WATCH_SEEDS).forEach { (entry, url) ->
                    val ageDays = (now() - entry.openedAt).coerceAtLeast(0L) / DAY_MS.toDouble()
                    val recency = max(0.25, exp(-ageDays / RECENCY_DAYS))
                    val completion = if (entry.durationMs > 0) {
                        (entry.positionMs.toDouble() / entry.durationMs).coerceIn(0.0, 1.0)
                    } else 0.5
                    add(Source(videoUrl = url, weight = (2.4 + 2.2 * completion) * recency))
                }
                relevantQueries.forEachIndexed { index, query ->
                    add(
                        Source(
                            query = query,
                            filter = topic?.filter ?: SearchFilter.VIDEOS,
                            weight = 1.2 + (relevantQueries.size - index) * 0.35,
                        ),
                    )
                }
                if (topic != null) {
                    broadQueries.forEachIndexed { index, query ->
                        add(
                            Source(
                                query = "$query ${topic.query}",
                                filter = topic.filter,
                                weight = 0.9 + (broadQueries.size - index) * 0.2,
                            ),
                        )
                    }
                }
            },
            topic = topic,
            // Don't put videos that were already opened straight back into any feed.
            seen = watched.mapTo(mutableSetOf<String>()) { it.second }.apply { addAll(exclude) },
        )
        fetchAll(cursor, onlyNext = false)
        return cursor
    }

    private suspend fun fetchAll(cursor: Cursor, onlyNext: Boolean) {
        val open = cursor.sources.filter { !it.exhausted && (it.started || !onlyNext) }
        if (open.isEmpty()) return
        val results = coroutineScope {
            open.map { source ->
                async { source to runCatching { load(source) }.getOrNull() }
            }.awaitAll()
        }
        results.forEach { (source, page) ->
            source.started = true
            if (page == null) {
                source.exhausted = true
                return@forEach
            }
            source.next = page.nextPage
            source.exhausted = page.nextPage == null
            add(cursor, source, page.items)
        }
    }

    private suspend fun load(source: Source): Page<MediaItem> {
        val video = source.videoUrl
        return if (video != null) {
            // Related videos are one-shot; the stream page doesn't expose a cursor for them.
            Page(media.details(video).related)
        } else {
            media.search(source.query.orEmpty(), source.filter, source.next)
        }
    }

    private fun add(cursor: Cursor, source: Source, items: List<MediaItem>) {
        items.forEachIndexed { rank, item ->
            if (item.kind != ItemKind.VIDEO || item.isShort) return@forEachIndexed
            if (!cursor.seen.add(item.url)) return@forEachIndexed
            val seed = source.weight / (1.0 + rank * 0.18)
            val views = ln(1.0 + item.viewCount.coerceAtLeast(0).toDouble()) / 14.0
            cursor.pool += Scored(item, seed + views + freshness(item.uploadDate))
        }
    }

    private fun buildPage(cursor: Cursor): Page<MediaItem> {
        if (cursor.pool.isEmpty()) return Page(emptyList(), null)
        val channelCounts = cursor.pool.groupingBy { it.item.uploader.lowercase() }.eachCount()
        val ranked = cursor.pool.sortedByDescending { candidate ->
            candidate.base + ln(1.0 + (channelCounts[candidate.item.uploader.lowercase()] ?: 1)) * 0.22
        }

        val picked = mutableListOf<Scored>()
        val perChannel = mutableMapOf<String, Int>()
        for (candidate in ranked) {
            if (picked.size >= PAGE_SIZE) break
            val channel = candidate.item.uploader.lowercase()
            if (perChannel.getOrDefault(channel, 0) >= MAX_PER_CHANNEL) continue
            picked += candidate
            perChannel[channel] = perChannel.getOrDefault(channel, 0) + 1
        }
        // With very few channels, a strict cap could leave the page half empty.
        if (picked.size < PAGE_SIZE) {
            for (candidate in ranked) {
                if (picked.size >= PAGE_SIZE) break
                if (candidate !in picked) picked += candidate
            }
        }
        cursor.pool.removeAll(picked.toSet())
        val hasMore = cursor.pool.isNotEmpty() || cursor.sources.any { !it.exhausted }
        return Page(picked.map { it.item }, if (hasMore) cursor else null)
    }

    private suspend fun fallback(page: Any?, topic: RecommendationTopic?, exclude: Set<String>): Page<MediaItem> {
        var cursor = page
        repeat(3) {
            val p = if (topic == null) {
                media.discover(cursor)
            } else {
                media.search(topic.query, topic.filter, cursor)
            }
            val items = if (exclude.isEmpty()) p.items else p.items.filterNot { it.url in exclude }
            if (items.isNotEmpty() || p.nextPage == null) {
                return p.copy(items = items, nextPage = p.nextPage?.let { FallbackCursor(it, topic, exclude) })
            }
            cursor = p.nextPage
        }
        return Page(emptyList(), null)
    }

    private fun RecommendationTopic.matches(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains(query.lowercase()) || keywords.any { lower.contains(it.lowercase()) }
    }

    private fun freshness(date: String?): Double {
        val text = date?.lowercase() ?: return 0.0
        return when {
            "minute" in text || "hour" in text || "just now" in text -> 1.2
            "day" in text -> {
                val days = Regex("""(\d+)\s*day""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                when {
                    days <= 2 -> 1.0
                    days <= 7 -> 0.7
                    days <= 30 -> 0.35
                    else -> 0.1
                }
            }
            "week" in text -> 0.5
            "month" in text -> 0.2
            else -> 0.0
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_SEARCH_SEEDS = 5
        const val MAX_TOPIC_SEARCH_SEEDS = 3
        const val MAX_BROAD_SEEDS = 2
        const val MAX_WATCH_SEEDS = 2
        const val MAX_PER_CHANNEL = 2
        const val TOPIC_SEED_WEIGHT = 1.7
        const val RECENCY_DAYS = 14.0
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
