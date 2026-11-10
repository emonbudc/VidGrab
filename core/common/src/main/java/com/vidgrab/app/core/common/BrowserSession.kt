package com.vidgrab.app.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

/**
 * The signed-in in-app browser session, reused for native requests.
 *
 * Passwords never pass through here: the Android app installs a provider that reads WebView's cookie store.
 * [headers] only ever returns data for the host the cookies belong to (Google/YouTube and the sites in
 * [LoginSites]), so the session cannot leak to other sites.
 */
object BrowserSession {
    fun interface Provider {
        /** Session headers for [url], or an empty map when there is no signed-in session. */
        fun headers(url: String): Map<String, String>
    }

    /** Cookies that mean a Google account is signed in. Other cookies are not forwarded. */
    private val AUTH_COOKIES = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO",
    )

    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    @Volatile
    private var provider: Provider? = null

    private val _generation = MutableStateFlow(0)

    /** Increments when the signed-in account changes, so screens showing account data can reload. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun install(provider: Provider?) {
        this.provider = provider
    }

    fun notifySessionChanged() {
        _generation.update { it + 1 }
    }

    /** Session headers for [url]; empty for hosts VidGrab has no sign-in for. */
    fun headers(url: String): Map<String, String> {
        if (!isSessionHost(url)) return emptyMap()
        return runCatching { provider?.headers(url).orEmpty() }.getOrDefault(emptyMap())
    }

    fun isSessionHost(url: String): Boolean = isGoogleHost(url) || LoginSites.forUrl(url) != null

    fun isGoogleHost(url: String): Boolean {
        val host = Urls.host(url)?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be") ||
            host == "google.com" || host.endsWith(".google.com") ||
            host == "googleapis.com" || host.endsWith(".googleapis.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    fun isYouTubeHost(url: String): Boolean {
        val host = Urls.host(url)?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be")
    }

    /**
     * YouTube's account authorization header. NewPipe already sends the session cookies, but YouTube only
     * treats restricted videos and the account home feed as signed in when the request also carries a
     * SAPISIDHASH for the session's SAPISID cookie. The hash is bound to one origin, so use the origin of
     * the actual host rather than www for every YouTube URL.
     */
    fun authHeaders(url: String): Map<String, String> {
        val origin = youtubeApiOrigin(url) ?: return emptyMap()
        val cookie = headers(url).entries.firstOrNull { it.key.equals("Cookie", true) }?.value ?: return emptyMap()
        val cookies = parseCookieHeader(cookie)
        if ((cookies["LOGIN_INFO"]?.length ?: 0) <= 10) return emptyMap()
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return emptyMap()
        if (sapisid.isBlank()) return emptyMap()
        val timestamp = System.currentTimeMillis() / 1000
        return linkedMapOf(
            "Origin" to origin,
            "Authorization" to "SAPISIDHASH ${sapisidHash(timestamp, sapisid, origin)}",
        )
    }

    private fun youtubeApiOrigin(url: String): String? {
        val host = try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
        return when (host) {
            "youtube.com", "www.youtube.com" -> YOUTUBE_ORIGIN
            "m.youtube.com" -> "https://m.youtube.com"
            "youtubei.googleapis.com" -> YOUTUBE_ORIGIN
            else -> null
        }
    }

    internal fun sapisidHash(timestamp: Long, sapisid: String, origin: String): String =
        "${timestamp}_${sha1("$timestamp $sapisid $origin")}"

    /** Non-reversible marker for the current sign-in, so cached signed-out results are not reused. */
    fun sessionKey(url: String): String {
        val cookie = headers(url).entries.firstOrNull { it.key.equals("Cookie", true) }?.value
            ?: return "anon"
        val cookies = parseCookieHeader(cookie)
        val identity = listOf("LOGIN_INFO", "SAPISID", "__Secure-3PAPISID", "SID")
            .mapNotNull { cookies[it] }
            .joinToString("|")
        return if (identity.isEmpty()) "anon" else sha1(identity).take(12)
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val valueInt = byte.toInt() and 0xff
            out[index * 2] = digits[valueInt ushr 4]
            out[index * 2 + 1] = digits[valueInt and 0x0f]
        }
        return String(out)
    }

    /**
     * [headers] with the browser session merged in. An explicit Cookie header is kept and the sign-in
     * cookies are appended, so the extractor's consent cookie and the account session both reach YouTube.
     */
    fun withSession(url: String, headers: Map<String, String>): Map<String, String> {
        val session = this.headers(url)
        if (session.isEmpty()) return headers
        val out = LinkedHashMap(session)
        for ((name, value) in headers) {
            if (!name.equals("Cookie", true)) out[name] = value
        }
        val sessionCookie = session.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        val explicitCookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        when {
            sessionCookie == null -> explicitCookie?.let { out["Cookie"] = it }
            explicitCookie == null -> out["Cookie"] = sessionCookie
            else -> out["Cookie"] = cookieHeader(explicitCookie, sessionCookie) ?: "$explicitCookie; $sessionCookie"
        }
        return out
    }

    /**
     * Combines cookie strings into one Cookie header, keeping the first value of each name (YouTube's own
     * copy before Google's). Returns null unless a sign-in cookie is present.
     */
    fun cookieHeader(vararg cookieStrings: String?): String? {
        val cookies = LinkedHashMap<String, String>()
        for (raw in cookieStrings) {
            if (raw == null) continue
            for (part in raw.split(';')) {
                val name = part.substringBefore('=', "").trim()
                if (name.isEmpty() || cookies.containsKey(name)) continue
                cookies[name] = part.substringAfter('=', "").trim()
            }
        }
        if (cookies.keys.none { it in AUTH_COOKIES }) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
