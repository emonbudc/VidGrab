package com.vidgrab.app

import android.webkit.CookieManager
import com.vidgrab.app.core.common.BrowserSession

/**
 * Bridges the in-app browser's Google/YouTube sign-in into native requests.
 *
 * The user signs in on YouTube in the browser; only the resulting cookies are read, never a password.
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
            if (!BrowserSession.isGoogleHost(url)) {
                emptyMap()
            } else {
                val cookies = COOKIE_URLS.map { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
                BrowserSession.cookieHeader(*cookies.toTypedArray())
                    ?.let { mapOf("Cookie" to it) }
                    .orEmpty()
            }
        }
    }
}
