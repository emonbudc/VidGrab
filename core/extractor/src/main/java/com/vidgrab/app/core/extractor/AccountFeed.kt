package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.SearchFilter

/** A feed that only exists while a site account is signed in. */
interface AccountFeed {
    val isAvailable: Boolean

    fun owns(page: Any?): Boolean

    suspend fun home(page: Any? = null): Page<MediaItem>

    /** Related videos for [url], or null when the account feed does not handle this page. */
    suspend fun related(url: String): List<MediaItem>? = null

    /** Search results personalized for the signed-in account, or null to use the extractor instead. */
    suspend fun search(query: String, filter: SearchFilter, page: Any? = null): Page<MediaItem>? = null

    fun ownsSearch(page: Any?): Boolean = false
}
