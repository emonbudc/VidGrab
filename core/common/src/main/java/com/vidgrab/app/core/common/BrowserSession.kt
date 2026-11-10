package com.vidgrab.app.core.common

/**
 * The signed-in in-app browser session, reused for native YouTube requests.
 *
 * Passwords never pass through here: the Android app installs a provider that reads WebView's cookie store.
 * [headers] only ever returns data for Google/YouTube hosts, so the session cannot leak to other sites.
 */
object BrowserSession {
    fun interface Provider {
        /** Session headers for [url], or an empty map when there is no signed-in session. */
        fun headers(url: String): Map<String, String>
    }

    /** Cookies that mean a Google account is signed in. Other cookies are not forwarded. */
    private val AUTH_COOKIES = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO",
    )

    @Volatile
    private var provider: Provider? = null

    fun install(provider: Provider?) {
        this.provider = provider
    }

    /** Session headers for [url]; empty for every non-Google host. */
    fun headers(url: String): Map<String, String> {
        if (!isGoogleHost(url)) return emptyMap()
        return runCatching { provider?.headers(url).orEmpty() }.getOrDefault(emptyMap())
    }

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
