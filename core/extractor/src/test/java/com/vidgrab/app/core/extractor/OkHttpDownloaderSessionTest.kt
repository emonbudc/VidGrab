package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.common.BrowserSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import java.util.concurrent.atomic.AtomicReference

class OkHttpDownloaderSessionTest {
    private val lastRequest = AtomicReference<okhttp3.Request>()
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            lastRequest.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @After
    fun clearSession() = BrowserSession.install(null)

    @Test
    fun addsTheBrowserCookieToYouTubeRequests() {
        BrowserSession.install { mapOf("Cookie" to "SID=session") }

        OkHttpDownloader(client).execute(Request.newBuilder().get("https://www.youtube.com/watch?v=x").build())

        assertEquals("SID=session", lastRequest.get().header("Cookie"))
    }

    @Test
    fun neverLeaksTheSessionToOtherSites() {
        BrowserSession.install { mapOf("Cookie" to "SID=session") }

        OkHttpDownloader(client).execute(Request.newBuilder().get("https://example.com/video").build())

        assertNull(lastRequest.get().header("Cookie"))
    }

    @Test
    fun mergesTheSessionWithNewPipesConsentCookie() {
        BrowserSession.install { mapOf("Cookie" to "SID=session") }

        OkHttpDownloader(client).execute(
            Request.newBuilder().get("https://www.youtube.com/watch?v=x").setHeader("Cookie", "SOCS=CAI").build(),
        )

        assertEquals("SOCS=CAI; SID=session", lastRequest.get().header("Cookie"))
    }

    @Test
    fun keepsAnExplicitCookieWhenThereIsNoSession() {
        OkHttpDownloader(client).execute(
            Request.newBuilder().get("https://www.youtube.com/watch?v=x").setHeader("Cookie", "explicit=1").build(),
        )

        assertEquals("explicit=1", lastRequest.get().header("Cookie"))
    }

    @Test
    fun addsSapisidHashToSignedInYouTubeApiRequests() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=abc") }

        OkHttpDownloader(client).execute(
            Request.newBuilder().get("https://www.youtube.com/youtubei/v1/player").build(),
        )

        assertNotNull(lastRequest.get().header("Authorization"))
        assertEquals("https://www.youtube.com", lastRequest.get().header("Origin"))
    }

    @Test
    fun mediaRequestsDoNotGetTheYouTubeApiAuthorization() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=abc") }

        OkHttpDownloader(client).execute(
            Request.newBuilder().get("https://rr1---sn-abc.googlevideo.com/videoplayback?itag=18").build(),
        )

        assertNull(lastRequest.get().header("Authorization"))
    }
}
