package com.vidgrab.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSitesTest {
    @Test
    fun youtubeNeedsARealLoginInfoCookie() {
        assertFalse(isSignedIn(LoginSites.YouTube, null))
        assertFalse(isSignedIn(LoginSites.YouTube, "PREF=a; VISITOR_INFO1_LIVE=b"))
        assertFalse(isSignedIn(LoginSites.YouTube, "LOGIN_INFO=short"))
        assertTrue(isSignedIn(LoginSites.YouTube, "PREF=a; LOGIN_INFO=${"x".repeat(40)}"))
    }

    @Test
    fun parsingKeepsTheFirstValueAndHandlesEqualsAndSpaces() {
        assertEquals(
            mapOf("SID" to "a", "LOGIN_INFO" to "b=c", "EMPTY" to ""),
            parseCookieHeader(" SID=a; SID=second; LOGIN_INFO=b=c ; EMPTY= "),
        )
        assertEquals(emptyMap<String, String>(), parseCookieHeader(null))
        assertEquals(emptyMap<String, String>(), parseCookieHeader("   "))
    }

    @Test
    fun hostsMatchOnlyTheSiteItself() {
        assertEquals(LoginSites.YouTube, LoginSites.forUrl("https://m.youtube.com/watch?v=x"))
        assertEquals(LoginSites.YouTube, LoginSites.forUrl("https://youtu.be/x"))
        assertEquals(LoginSites.Instagram, LoginSites.forUrl("https://www.instagram.com/p/x"))
        assertEquals(LoginSites.Facebook, LoginSites.forUrl("https://m.facebook.com/watch"))
        assertEquals(LoginSites.TikTok, LoginSites.forUrl("https://www.tiktok.com/@user/video/1"))
        assertNull(LoginSites.forUrl("https://notyoutube.com/watch?v=x"))
        assertNull(LoginSites.forUrl("https://youtube.com.example.com/watch?v=x"))
        assertNull(LoginSites.forUrl("https://example.com/?next=youtube.com"))
        assertNull(LoginSites.forUrl(null))
    }

    @Test
    fun otherSitesUseTheirOwnSignInCookie() {
        assertTrue(isSignedIn(LoginSites.Instagram, "csrftoken=a; sessionid=${"s".repeat(20)}"))
        assertTrue(isSignedIn(LoginSites.Facebook, "c_user=100001234567890"))
        assertTrue(isSignedIn(LoginSites.TikTok, "ttwid=x; sessionid=${"s".repeat(20)}"))
        assertFalse(isSignedIn(LoginSites.Instagram, "csrftoken=a"))
        assertFalse(isSignedIn(LoginSites.Facebook, "datr=a; sb=b"))
    }

    @Test
    fun lookupById() {
        assertEquals(LoginSites.YouTube, LoginSites.byId("youtube"))
        assertEquals(LoginSites.TikTok, LoginSites.byId("tiktok"))
        assertNull(LoginSites.byId("unknown"))
    }
}
