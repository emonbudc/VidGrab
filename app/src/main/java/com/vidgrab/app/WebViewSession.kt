package com.vidgrab.app

import android.webkit.CookieManager
import com.vidgrab.app.core.common.BrowserSession
import com.vidgrab.app.core.common.LoginSites
import com.vidgrab.app.core.common.isSignedIn
import com.vidgrab.app.core.common.parseCookieHeader

/**
 * Bridges the in-app browser's sign-ins into native requests.
 *
 * The user signs in on the site's own page; only the resulting cookies are read, never a password.
 * Reading the cookie store on every request keeps the native session in sync with the browser.
 */
object WebViewSession {
    private val COOKIE_URLS = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://www.google.com",
        "https://accounts.google.com",
    )

    fun install() {
        BrowserSession.install { url ->
            when {
                BrowserSession.isGoogleHost(url) -> googleHeaders()
                else -> siteHeaders(url)
            }
        }
    }

    private fun googleHeaders(): Map<String, String> {
        val cookies = COOKIE_URLS.map { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
        return BrowserSession.cookieHeader(*cookies.toTypedArray())
            ?.let { mapOf("Cookie" to it) }
            .orEmpty()
    }

    private fun siteHeaders(url: String): Map<String, String> {
        val site = LoginSites.forUrl(url) ?: return emptyMap()
        val cookies = site.cookieUrls
            .mapNotNull { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
            .joinToString("; ")
        if (!isSignedIn(site, cookies)) return emptyMap()
        val merged = parseCookieHeader(cookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
        return mapOf("Cookie" to merged)
    }
}
