package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.model.SearchFilter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hits the real network; run manually with -Pnetwork-tests or from the IDE. */
class ExtractorSmokeTest {
    private val ex by lazy { NewPipeMediaExtractor(OkHttpClient()) }

    @Test
    fun liveSmoke() = runBlocking {
        if (System.getenv("NETWORK_TESTS") != "1") return@runBlocking
        val s = ex.search("coldplay yellow", SearchFilter.ALL)
        println("search: ${s.items.size} first=${s.items.firstOrNull()?.title} more=${s.hasMore}")
        assertTrue(s.items.isNotEmpty())
        println("suggest: ${ex.suggestions("coldp").take(5)}")
        val v = s.items.first { it.kind == com.vidgrab.app.core.model.ItemKind.VIDEO }
        val d = ex.details(v.url)
        println("details: ${d.item.title} play=${d.playbackUrl != null} related=${d.related.size}")
        d.options.forEach { println("  ${it.kind} ${it.title} ${it.info} size=${it.totalSize} merge=${it.audioUrl != null}") }
        val c = runCatching { ex.comments(v.url) }
        println("comments: ${c.getOrNull()?.items?.size} ${c.exceptionOrNull()}")
    }

    @Test
    fun messyWatchUrlIsAccepted() = runBlocking {
        if (System.getenv("NETWORK_TESTS") != "1") return@runBlocking
        val d = ex.details("https://m.youtube.com/watch?v=1PtOb0pMYW8&list=RD1PtOb0pMYW8&start_radio=1&pp=ygUPY29sZHBsYXkgeWVsbG93")
        println("messy url ok: ${d.item.title}")
        assertTrue(d.options.isNotEmpty())
    }
}
