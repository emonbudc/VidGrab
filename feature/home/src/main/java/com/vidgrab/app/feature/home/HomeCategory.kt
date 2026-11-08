package com.vidgrab.app.feature.home

import com.vidgrab.app.core.model.SearchFilter

enum class HomeCategory(
    val label: String,
    val query: String? = null,
    val filter: SearchFilter = SearchFilter.VIDEOS,
) {
    ALL("All"),
    MUSIC("Music", "music", SearchFilter.MUSIC),
    NEWS("News", "news", SearchFilter.VIDEOS),
    MUSIC_OF_BENGAL("Music of Bengal", "bangla music", SearchFilter.MUSIC),
    NATOK("Natok", "bangla natok", SearchFilter.VIDEOS),
}
