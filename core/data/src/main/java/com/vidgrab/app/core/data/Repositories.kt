package com.vidgrab.app.core.data

import com.vidgrab.app.core.model.AppSettings
import com.vidgrab.app.core.model.Bookmark
import com.vidgrab.app.core.model.ChannelDetails
import com.vidgrab.app.core.model.Comment
import com.vidgrab.app.core.model.DownloadTask
import com.vidgrab.app.core.model.GalleryLibrary
import com.vidgrab.app.core.model.HistoryEntry
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.PlaylistDetails
import com.vidgrab.app.core.model.SearchFilter
import com.vidgrab.app.core.model.Site
import com.vidgrab.app.core.model.VideoDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MediaRepository {
    suspend fun suggestions(query: String): List<String>
    suspend fun search(query: String, filter: SearchFilter, page: Any? = null): Page<MediaItem>
    suspend fun discover(page: Any? = null): Page<MediaItem>
    suspend fun details(url: String, forceRefresh: Boolean = false): VideoDetails
    suspend fun comments(url: String, page: Any? = null): Page<Comment>
    suspend fun channel(url: String, page: Any? = null): ChannelDetails
    suspend fun playlist(url: String, page: Any? = null): PlaylistDetails
    fun canExtract(url: String): Boolean
    /** VIDEO/CHANNEL/PLAYLIST when the URL is a supported page, null otherwise. */
    fun linkKind(url: String): com.vidgrab.app.core.model.ItemKind?
}

interface LibraryRepository {
    val downloads: Flow<List<DownloadTask>>
    val trash: Flow<List<DownloadTask>>
    fun observe(id: Long): Flow<DownloadTask?>
    suspend fun get(id: Long): DownloadTask?
    suspend fun moveToTrash(ids: List<Long>)
    suspend fun restore(ids: List<Long>)
    suspend fun deleteForever(ids: List<Long>)
    suspend fun purgeExpiredTrash()
    suspend fun setVault(ids: List<Long>, inVault: Boolean)
    /** Moves files that older versions only flagged as vaulted out of the public download folder. */
    suspend fun migrateVault()
    suspend fun rename(id: Long, newTitle: String): Boolean
    suspend fun savePosition(id: Long, positionMs: Long)
    suspend fun addLocalFile(task: DownloadTask): Long
    val deletedHistory: Flow<List<com.vidgrab.app.core.model.DeletedDownload>>
    /** Adds media files found in the download folder that aren't in the library yet (e.g. after reinstalling). Returns how many. */
    suspend fun importFolder(dir: String): Int
    /**
     * Moves finished downloads whose file is gone from the device (deleted in another app, a file manager…) into the
     * deleted history, so they can be downloaded again. Returns how many.
     */
    suspend fun archiveMissing(): Int
    suspend fun removeDeletedHistory(ids: List<Long>)
    suspend fun clearDeletedHistory()
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
    val defaultDownloadDir: String
}

interface BrowserRepository {
    val sites: Flow<List<Site>>
    val history: Flow<List<HistoryEntry>>
    val bookmarks: Flow<List<Bookmark>>
    fun isBookmarked(url: String): Flow<Boolean>
    suspend fun addSite(name: String, url: String)
    suspend fun deleteSites(ids: List<Long>)
    suspend fun recordVisit(title: String, url: String)
    suspend fun deleteHistory(id: Long)
    suspend fun clearHistory()
    suspend fun toggleBookmark(title: String, url: String)
    suspend fun removeBookmark(url: String)
}

interface SearchHistoryRepository {
    val recent: Flow<List<String>>
    suspend fun add(query: String)
    suspend fun remove(query: String)
    suspend fun clear()
}

/** Where played videos were left, for resuming. Holds at most [MAX_ENTRIES]; the least recently opened go first. */
interface PlaybackHistoryRepository {
    /** Most recently opened first. */
    val entries: Flow<List<com.vidgrab.app.core.model.PlayHistoryEntry>>
    suspend fun all(): List<com.vidgrab.app.core.model.PlayHistoryEntry>
    /** Remembers [positionMs] for [key]; [opened] also marks it as just opened (for eviction order). */
    suspend fun save(key: String, title: String, positionMs: Long, durationMs: Long, opened: Boolean = false)
    suspend fun clear()

    companion object {
        const val MAX_ENTRIES = 300
    }
}

/** "Watch later": online videos saved to open later. */
interface SavedVideosRepository {
    val saved: Flow<List<com.vidgrab.app.core.model.SavedVideo>>
    fun isSaved(url: String): Flow<Boolean>
    suspend fun save(video: com.vidgrab.app.core.model.SavedVideo)
    suspend fun remove(urls: List<String>)
}


/** Photos and videos on the device (MediaStore). Loaded in pages, kept fresh by a content observer. */
interface GalleryRepository {
    /** Null until the first page has loaded; afterwards grows page by page until [GalleryLibrary.complete]. */
    val library: StateFlow<GalleryLibrary?>
    /** Starts loading (if not yet) and watching for changes. Call again after permissions change. */
    fun load(force: Boolean = false)
    /**
     * MediaStore id of the file at [path], scanning it into MediaStore first when it isn't there yet (a download that
     * finished moments ago). Null when the file is outside MediaStore's reach (app-private storage) or unreadable.
     */
    suspend fun mediaIdFor(path: String): Long?
}
