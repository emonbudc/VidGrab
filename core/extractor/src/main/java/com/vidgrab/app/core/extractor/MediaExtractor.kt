package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.model.ChannelDetails
import com.vidgrab.app.core.model.Comment
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.MediaSection
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.PlaylistDetails
import com.vidgrab.app.core.model.SearchFilter
import com.vidgrab.app.core.model.VideoDetails

/** Platform-agnostic contract for online media lookups (search, feeds, stream info). */
interface MediaExtractor {
    fun canHandle(url: String): Boolean
    fun linkKind(url: String): ItemKind?
    suspend fun suggestions(query: String): List<String>
    suspend fun search(query: String, filter: SearchFilter, page: Any? = null): Page<MediaItem>
    suspend fun trending(page: Any? = null): Page<MediaItem>
    /** Mixed results for randomly chosen searches, so the home feed changes instead of repeating one kiosk list. */
    suspend fun discover(page: Any? = null): Page<MediaItem>
    suspend fun musicHome(): List<MediaSection>
    suspend fun details(url: String): VideoDetails
    suspend fun comments(url: String, page: Any? = null): Page<Comment>
    suspend fun channel(url: String, page: Any? = null): ChannelDetails
    suspend fun playlist(url: String, page: Any? = null): PlaylistDetails
}
