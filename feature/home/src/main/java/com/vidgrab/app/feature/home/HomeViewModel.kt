package com.vidgrab.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.core.data.MediaRepository
import com.vidgrab.app.core.data.SearchHistoryRepository
import com.vidgrab.app.core.model.DownloadStatus
import com.vidgrab.app.core.model.DownloadTask
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.MediaSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedUi(
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Any? = null,
)

data class MusicUi(
    val sections: List<MediaSection> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(
    private val media: MediaRepository,
    library: com.vidgrab.app.core.data.LibraryRepository,
    private val settings: com.vidgrab.app.core.data.SettingsRepository,
    private val history: SearchHistoryRepository,
    private val downloads: com.vidgrab.app.core.download.DownloadManager,
) : ViewModel() {
    private var feedLoadedAt = 0L

    /** Settings → Trending on home (off by default). */
    val showTrending = settings.settings.map { it.showTrending }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val clipboardDetection = settings.settings.map { it.clipboardDetection }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)

    fun pause(id: Long) = downloads.pause(id)
    fun resume(id: Long) = downloads.resume(id)

    /**
     * Links the plain download sheet can't handle by itself: playlists and channels (their page lists the videos) and
     * Instagram posts (read in the browser, photos included). These open in the app instead.
     */
    fun opensInApp(url: String): Boolean {
        val u = com.vidgrab.app.core.common.Urls.normalize(url)
        val kind = media.linkKind(u)
        return kind == com.vidgrab.app.core.model.ItemKind.PLAYLIST || kind == com.vidgrab.app.core.model.ItemKind.CHANNEL ||
            com.vidgrab.app.core.common.Urls.platformOf(u) == com.vidgrab.app.core.common.Platform.INSTAGRAM
    }

    /** Latest finished downloads for the "Recent downloads" row. */
    val recent = library.downloads
        .map { l -> l.filter { it.status == DownloadStatus.COMPLETED && !it.inVault && it.deletedAt == 0L }.take(10) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeCount = library.downloads
        .map { l -> l.count { it.status.isActive && it.deletedAt == 0L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val savedCount = library.downloads
        .map { l -> l.count { it.status == DownloadStatus.COMPLETED && !it.inVault && it.deletedAt == 0L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeTask: StateFlow<DownloadTask?> = library.downloads
        .map { l -> l.firstOrNull { it.status.isActive && it.deletedAt == 0L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recentSearches = history.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun recordSearch(query: String) {
        if (query.isNotBlank()) viewModelScope.launch { history.add(query) }
    }

    fun clearRecentSearches() = viewModelScope.launch { history.clear() }

    fun forgetSearch(query: String) = viewModelScope.launch { history.remove(query) }

    private val _feed = MutableStateFlow(FeedUi())
    val feed: StateFlow<FeedUi> = _feed
    private val _music = MutableStateFlow(MusicUi())
    val music: StateFlow<MusicUi> = _music

    init {
        viewModelScope.launch {
            settings.settings.map { it.contentRegion }.distinctUntilChanged().drop(1).collect {
                if (_feed.value.items.isNotEmpty()) loadFeed(refresh = true)
                if (_music.value.sections.isNotEmpty()) loadMusic()
            }
        }
    }

    fun ensureFeed(force: Boolean = false) {
        val s = _feed.value
        if (s.loading || s.refreshing) return
        val stale = android.os.SystemClock.elapsedRealtime() - feedLoadedAt > FEED_MAX_AGE_MS
        if (s.items.isEmpty() || force || stale) loadFeed(refresh = s.items.isNotEmpty())
    }

    fun loadFeed(refresh: Boolean) {
        _feed.update { it.copy(loading = !refresh, refreshing = refresh, error = null) }
        viewModelScope.launch {
            runCatching { media.discover() }
                .onSuccess { p ->
                    feedLoadedAt = android.os.SystemClock.elapsedRealtime()
                    _feed.value = FeedUi(items = p.items.distinctBy { it.url }, nextPage = p.nextPage)
                }
                .onFailure { e -> _feed.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Error") } }
        }
    }

    fun loadMoreFeed() {
        val s = _feed.value
        if (s.loadingMore || s.nextPage == null) return
        _feed.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { media.discover(s.nextPage) }
                .onSuccess { p -> _feed.update { it.copy(items = (it.items + p.items).distinctBy { i -> i.url }, nextPage = p.nextPage, loadingMore = false) } }
                .onFailure { _feed.update { it.copy(loadingMore = false, nextPage = null) } }
        }
    }

    fun ensureMusic() {
        if (_music.value.sections.isEmpty() && !_music.value.loading) loadMusic()
    }

    private companion object {
        const val FEED_MAX_AGE_MS = 2L * 60 * 1000
    }

    fun loadMusic() {
        _music.value = MusicUi(loading = true)
        viewModelScope.launch {
            runCatching { media.musicHome() }
                .onSuccess { _music.value = MusicUi(sections = it, error = if (it.isEmpty()) "Nothing to show right now" else null) }
                .onFailure { e -> _music.value = MusicUi(error = e.message ?: "Error") }
        }
    }
}
