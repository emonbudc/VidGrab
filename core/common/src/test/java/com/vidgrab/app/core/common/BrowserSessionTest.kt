package com.vidgrab.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionTest {
    @Test
    fun sessionOnlyGoesToGoogleHosts() {
        assertTrue(BrowserSession.isGoogleHost("https://www.youtube.com/watch?v=x"))
        assertTrue(BrowserSession.isGoogleHost("https://m.youtube.com/watch?v=x"))
        assertTrue(BrowserSession.isGoogleHost("https://youtu.be/x"))
        assertTrue(BrowserSession.isGoogleHost("https://accounts.google.com/signin"))
        assertTrue(BrowserSession.isGoogleHost("https://youtubei.googleapis.com/youtubei/v1/player"))
        assertTrue(BrowserSession.isGoogleHost("https://rr1---sn-abc.googlevideo.com/videoplayback"))
        assertFalse(BrowserSession.isGoogleHost("https://notyoutube.com/watch?v=x"))
        assertFalse(BrowserSession.isGoogleHost("https://youtube.com.example.com/watch?v=x"))
        assertFalse(BrowserSession.isGoogleHost("https://example.com/?next=youtube.com"))
    }

    @Test
    fun providerIsNotCalledForOtherSites() {
        var calls = 0
        BrowserSession.install {
            calls++
            mapOf("Cookie" to "SID=x")
        }
        try {
            assertEquals(emptyMap<String, String>(), BrowserSession.headers("https://example.com/video"))
            assertEquals(0, calls)
            assertEquals(mapOf("Cookie" to "SID=x"), BrowserSession.headers("https://www.youtube.com/watch?v=x"))
            assertEquals(1, calls)
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun providerAlsoRunsForSupportedLoginSites() {
        var calls = 0
        BrowserSession.install {
            calls++
            mapOf("Cookie" to "sessionid=x")
        }
        try {
            assertEquals(emptyMap<String, String>(), BrowserSession.headers("https://example.com/video"))
            assertEquals(0, calls)
            assertEquals(
                mapOf("Cookie" to "sessionid=x"),
                BrowserSession.headers("https://www.instagram.com/reel/x"),
            )
            assertEquals(1, calls)
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun youtubeHostsAreNarrowerThanGoogleHosts() {
        assertTrue(BrowserSession.isYouTubeHost("https://youtu.be/x"))
        assertTrue(BrowserSession.isYouTubeHost("https://m.youtube.com/watch?v=x"))
        assertFalse(BrowserSession.isYouTubeHost("https://accounts.google.com/signin"))
        assertFalse(BrowserSession.isYouTubeHost("https://notyoutube.com/watch?v=x"))
    }

    @Test
    fun withSessionMergesCookiesAndKeepsExplicitHeaders() {
        BrowserSession.install { mapOf("Cookie" to "SID=session") }
        try {
            val headers = BrowserSession.withSession(
                "https://www.youtube.com/watch?v=x",
                mapOf("Cookie" to "SOCS=CAI", "Referer" to "https://example.com"),
            )
            assertEquals("SOCS=CAI; SID=session", headers["Cookie"])
            assertEquals("https://example.com", headers["Referer"])
            assertEquals(mapOf("A" to "B"), BrowserSession.withSession("https://example.com", mapOf("A" to "B")))
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun youtubeCookiesWinAndSignInCookieIsRequired() {
        val header = BrowserSession.cookieHeader(
            "PREF=a; SID=youtube; SAPISID=yt-sapisid",
            "SID=google; HSID=h; __Secure-1PSID=s",
        )
        assertEquals("PREF=a; SID=youtube; SAPISID=yt-sapisid; HSID=h; __Secure-1PSID=s", header)
        assertNull(BrowserSession.cookieHeader("PREF=a; VISITOR_INFO1_LIVE=b"))
        assertNull(BrowserSession.cookieHeader(null))
    }

    @Test
    fun aFailingProviderDoesNotBreakExtraction() {
        BrowserSession.install { error("cookie store unavailable") }
        try {
            assertEquals(emptyMap<String, String>(), BrowserSession.headers("https://www.youtube.com/watch?v=x"))
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun authHeadersAddSapisidHashForSignedInYouTube() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=abc") }
        try {
            val headers = BrowserSession.authHeaders("https://www.youtube.com/youtubei/v1/browse")
            assertEquals("https://www.youtube.com", headers["Origin"])
            assertEquals("https://www.youtube.com", headers["X-Origin"])
            assertEquals("0", headers["X-Goog-AuthUser"])
            assertEquals("true", headers["X-Youtube-Bootstrap-Logged-In"])
            val authorization = headers["Authorization"] ?: error("missing Authorization")
            val match = Regex("""SAPISIDHASH (\d+)_([0-9a-f]{40})""").matchEntire(authorization)
            assertNotNull(match)
            val timestamp = match!!.groupValues[1].toLong()
            assertEquals(
                BrowserSession.sapisidHash(timestamp, "abc", "https://www.youtube.com"),
                authorization.removePrefix("SAPISIDHASH "),
            )
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun authHeadersMatchTheOriginOfTheYouTubeHost() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=abc") }
        try {
            val headers = BrowserSession.authHeaders("https://m.youtube.com/youtubei/v1/browse")
            assertEquals("https://m.youtube.com", headers["Origin"])
            val authorization = headers["Authorization"] ?: error("missing Authorization")
            val match = Regex("""SAPISIDHASH (\d+)_([0-9a-f]{40})""").matchEntire(authorization)!!
            assertEquals(
                BrowserSession.sapisidHash(match.groupValues[1].toLong(), "abc", "https://m.youtube.com"),
                authorization.removePrefix("SAPISIDHASH "),
            )
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun authHeadersNeedARealAccountAndOnlyGoToYouTubeApis() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=short; SAPISID=abc") }
        try {
            assertEquals(emptyMap<String, String>(), BrowserSession.authHeaders("https://www.youtube.com/youtubei/v1/browse"))
        } finally {
            BrowserSession.install(null)
        }
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=abc") }
        try {
            assertEquals(emptyMap<String, String>(), BrowserSession.authHeaders("https://www.instagram.com/"))
            assertEquals(emptyMap<String, String>(), BrowserSession.authHeaders("https://rr1.googlevideo.com/videoplayback"))
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun authHeadersAlsoAcceptTheSecurePapisidCookie() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; __Secure-3PAPISID=abc") }
        try {
            assertTrue(BrowserSession.authHeaders("https://youtubei.googleapis.com/youtubei/v1/player").containsKey("Authorization"))
        } finally {
            BrowserSession.install(null)
        }
    }

    @Test
    fun sapisidHashMatchesGoogleFormat() {
        assertEquals(
            "1234567890_ecab8b81ae1305ed18cdd3833fa9ebedcb13c976",
            BrowserSession.sapisidHash(1234567890, "abc", "https://www.youtube.com"),
        )
    }

    @Test
    fun sessionKeyChangesWhenTheAccountChanges() {
        BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=one") }
        try {
            val first = BrowserSession.sessionKey("https://www.youtube.com/watch?v=x")
            BrowserSession.install { mapOf("Cookie" to "LOGIN_INFO=abcdefghijkl; SAPISID=two") }
            val second = BrowserSession.sessionKey("https://www.youtube.com/watch?v=x")
            assertTrue(first != second)
        } finally {
            BrowserSession.install(null)
        }
    }
}
