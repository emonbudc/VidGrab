package com.vidgrab.app.feature.browser

import android.webkit.CookieManager
import com.vidgrab.app.core.common.BrowserSession
import com.vidgrab.app.core.common.LoginSite
import com.vidgrab.app.core.common.LoginSites
import com.vidgrab.app.core.common.isSignedIn as signedIn
import com.vidgrab.app.core.common.parseCookieHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reads the WebView cookie store to know which sites have an account attached. The cookies stay in Android's
 * CookieManager; VidGrab never reads a password.
 */
object SiteSessions {
    private val _states = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val states: StateFlow<Map<String, Boolean>> = _states.asStateFlow()

    private val lastAccount = mutableMapOf<String, String?>()

    fun refresh() {
        LoginSites.all.forEach(::refresh)
    }

    fun refresh(site: LoginSite) {
        val cookies = cookieString(site)
        val signedIn = signedIn(site, cookies)
        val account = parseCookieHeader(cookies)[site.signedInCookie]
        val hadPrevious = lastAccount.containsKey(site.id)
        val previous = lastAccount.put(site.id, account)
        _states.update { it + (site.id to signedIn) }
        if (previous != account && (hadPrevious || account != null)) BrowserSession.notifySessionChanged()
    }

    fun isSignedIn(site: LoginSite): Boolean = signedIn(site, cookieString(site))

    /** The live value of the sign-in cookie, or null when this site is signed out. */
    fun signedInCookie(site: LoginSite): String? =
        parseCookieHeader(cookieString(site))[site.signedInCookie]?.takeIf { it.length > 10 }

    fun signOut(site: LoginSite) {
        val manager = CookieManager.getInstance()
        for (url in site.cookieUrls) {
            for (name in site.authCookies) {
                runCatching { manager.setCookie(url, "$name=; Max-Age=0; Path=/") }
            }
        }
        runCatching { manager.flush() }
        refresh(site)
    }

    private fun cookieString(site: LoginSite): String = site.cookieUrls
        .mapNotNull { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
        .joinToString("; ")
}
