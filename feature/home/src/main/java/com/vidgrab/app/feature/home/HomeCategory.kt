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
            "playlist", "audio", "instrumental", "beats", "গান", "সঙ্গীত", "শিল্পী", "অ্যালবাম", "সুর",
        ),
    ),
    NEWS(
        "News", "news", SearchFilter.VIDEOS,
        listOf(
            "news", "breaking", "headline", "headlines", "politics", "election", "parliament", "weather",
            "report", "bulletin", "খবর", "সংবাদ", "রাজনীতি", "নির্বাচন", "আন্তর্জাতিক", "প্রতিবেদন",
        ),
    ),
    MOVIES(
        "Movies", "movies", SearchFilter.VIDEOS,
        listOf(
            "movie", "movies", "film", "films", "cinema", "trailer", "teaser", "bollywood", "tollywood",
            "hollywood", "ott", "web series", "picture", "সিনেমা", "চলচ্চিত্র", "মুভি", "ট্রেইলার",
        ),
    ),
    NATOK(
        "Natok", "bangla natok", SearchFilter.VIDEOS,
        listOf(
            "natok", "drama", "telefilm", "serial", "episode", "bangla natok", "নাটক", "টেলিফিল্ম",
            "ধারাবাহিক", "অভিনয়", "পর্ব",
        ),
    );

    val topic: RecommendationTopic? get() = query?.let { RecommendationTopic(it, filter, keywords) }
}
