package com.vidgrab.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.core.common.BrowserSession
import com.vidgrab.app.core.data.PlaybackHistoryRepository
import com.vidgrab.app.core.data.RecommendationRepository
import com.vidgrab.app.core.data.SearchHistoryRepository
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUi(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Any? = null,
)

class HomeViewModel(
    private val recommendations: RecommendationRepository,
    private val history: SearchHistoryRepository,
    private val playHistory: PlaybackHistoryRepository,
    private val downloads: com.vidgrab.app.core.download.DownloadManager,
) : ViewModel() {
    private var feedLoadedAt = 0L
    private var feedRequest = 0
    private val feedServed = linkedSetOf<String>()

    private val _category = MutableStateFlow(HomeCategory.ALL)
    val category: StateFlow<HomeCategory> = _category.asStateFlow()

    fun pause(id: Long) = downloads.pause(id)
    fun resume(id: Long) = downloads.resume(id)

    private val _feed = MutableStateFlow(FeedUi())
    val feed: StateFlow<FeedUi> = _feed

    init {
        viewModelScope.launch {
            merge(
                history.recent.map { list -> "s:" + list.joinToString("\u0000") }.distinctUntilChanged().drop(1),
                playHistory.entries.map { list -> "p:" + list.joinToString("\u0000") { "${it.key}:${it.openedAt}" } }
                    .distinctUntilChanged().drop(1),
            ).conflate().collect {
                delay(1_500)
                if (feedLoadedAt == 0L) return@collect
                if (!_feed.value.loading && !_feed.value.refreshing) {
                    loadFeed(refresh = _feed.value.items.isNotEmpty())
                }
            }
        }
        viewModelScope.launch {
            BrowserSession.generation.drop(1).collect {
                if (_category.value != HomeCategory.ALL) return@collect
                feedLoadedAt = 0L
                feedServed.clear()
                loadFeed(refresh = _feed.value.items.isNotEmpty())
            }
        }
    }

    fun selectCategory(category: HomeCategory) {
        if (_category.value == category) return
        _category.value = category
        feedLoadedAt = 0L
        feedServed.clear()
        _feed.value = FeedUi(loading = true)
        loadFeed(refresh = false)
    }

    fun ensureFeed(force: Boolean = false) {
        val s = _feed.value
        if (s.loading || s.refreshing) return
        val stale = android.os.SystemClock.elapsedRealtime() - feedLoadedAt > FEED_MAX_AGE_MS
        if (s.items.isEmpty() || force || stale) loadFeed(refresh = s.items.isNotEmpty())
    }

    fun loadFeed(refresh: Boolean) {
        val requested = _category.value
        val request = ++feedRequest
        val exclude = if (refresh) feedServed.toSet() else emptySet()
        if (!refresh) feedServed.clear()
        _feed.update { it.copy(loading = !refresh, refreshing = refresh, error = null, loadingMore = false) }
        viewModelScope.launch {
            runCatching { pageFor(requested, null, exclude) }
                .onSuccess { first ->
                    if (request != feedRequest || requested != _category.value) return@onSuccess
                    val p = if (refresh && first.items.isEmpty() && exclude.isNotEmpty()) {
                        pageFor(requested, null, emptySet())
                    } else {
                        first
                    }
                    if (request != feedRequest || requested != _category.value) return@onSuccess
                    feedLoadedAt = android.os.SystemClock.elapsedRealtime()
                    val items = p.items.distinctBy { it.url }
                    rememberServed(items.map { it.url })
                    _feed.value = FeedUi(items = items, nextPage = p.nextPage)
                }
                .onFailure { e ->
                    if (request != feedRequest || requested != _category.value) return@onFailure
                    _feed.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Error") }
                }
        }
    }

    fun loadMoreFeed() {
        val s = _feed.value
        val requested = _category.value
        if (s.loadingMore || s.nextPage == null) return
        val request = feedRequest
        _feed.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { pageFor(requested, s.nextPage) }
                .onSuccess { p ->
                    if (request != feedRequest || requested != _category.value) return@onSuccess
                    rememberServed(p.items.map { it.url })
                    _feed.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, nextPage = p.nextPage, loadingMore = false) }
                }
                .onFailure {
                    if (request != feedRequest || requested != _category.value) return@onFailure
                    _feed.update { it.copy(loadingMore = false, nextPage = null) }
                }
        }
    }

    private suspend fun pageFor(category: HomeCategory, page: Any?, exclude: Set<String> = emptySet()): Page<MediaItem> {
        val result = recommendations.feed(page, category.topic, exclude)
        return result.copy(items = result.items.filter { it.kind == com.vidgrab.app.core.model.ItemKind.VIDEO && !it.isShort })
    }

    private fun rememberServed(urls: List<String>) {
        feedServed.addAll(urls)
        while (feedServed.size > MAX_SERVED_URLS) {
            val oldest = feedServed.first()
            feedServed.remove(oldest)
        }
    }

    private companion object {
        const val FEED_MAX_AGE_MS = 2L * 60 * 1000
        const val MAX_SERVED_URLS = 200
    }
}
