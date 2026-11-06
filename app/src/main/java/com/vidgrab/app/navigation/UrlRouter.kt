package com.vidgrab.app.navigation

import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.data.MediaRepository
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.navigation.BrowserRoute
import com.vidgrab.app.core.navigation.ChannelRoute
import com.vidgrab.app.core.navigation.PlaylistRoute
import com.vidgrab.app.core.navigation.StatusSaverRoute
import com.vidgrab.app.core.navigation.VideoRoute

/** Decides which screen opens a URL: native pages for supported links, the browser otherwise. */
class UrlRouter(private val media: MediaRepository, private val sheet: DownloadSheetController) {
    /**
     * A link the user pasted or shared means "download this": native pages open with the format sheet on top,
     * any other site opens in the browser, which pops the sheet as soon as it finds the page's video.
     */
    fun routeForPasted(raw: String): Any {
        val url = Urls.normalize(raw)
        return when (val r = routeFor(url)) {
            is VideoRoute -> r.also { sheet.open(url) }
            is BrowserRoute -> r.copy(autoDownload = true)
            else -> r
        }
    }

    fun routeFor(raw: String): Any {
        if (raw == "vidgrab://status-saver") return StatusSaverRoute
        val url = Urls.normalize(raw)
        return when (media.linkKind(url)) {
            ItemKind.VIDEO -> VideoRoute(url)
            ItemKind.PLAYLIST -> PlaylistRoute(url)
            ItemKind.CHANNEL -> ChannelRoute(url)
            null -> BrowserRoute(url)
        }
    }
}
