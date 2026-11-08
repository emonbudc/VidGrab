package com.vidgrab.app.feature.home

import com.vidgrab.app.core.data.RecommendationTopic
import com.vidgrab.app.core.model.SearchFilter

enum class HomeCategory(
    val label: String,
    val query: String? = null,
    val filter: SearchFilter = SearchFilter.VIDEOS,
    val keywords: List<String> = emptyList(),
) {
    ALL("For you"),
    MUSIC(
        "Music", "music", SearchFilter.MUSIC,
        listOf(
            "song", "songs", "music", "singer", "album", "lofi", "remix", "cover", "band", "concert",
            "playlist", "audio", "instrumental", "beats",
        ),
    ),
    NEWS(
        "News", "news", SearchFilter.VIDEOS,
        listOf(
            "news", "breaking", "headline", "headlines", "politics", "election", "parliament", "weather",
            "report", "bulletin",
        ),
    ),
    MOVIES(
        "Movies", "movies", SearchFilter.VIDEOS,
        listOf(
            "movie", "movies", "film", "films", "cinema", "trailer", "teaser", "bollywood", "tollywood",
            "hollywood", "ott", "web series", "picture",
        ),
    );

    val topic: RecommendationTopic? get() = query?.let { RecommendationTopic(it, filter, keywords) }
}
