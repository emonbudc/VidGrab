package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page

/** A home feed that only exists while a site account is signed in. */
interface AccountFeed {
    val isAvailable: Boolean

    fun owns(page: Any?): Boolean

    suspend fun home(page: Any? = null): Page<MediaItem>
}
