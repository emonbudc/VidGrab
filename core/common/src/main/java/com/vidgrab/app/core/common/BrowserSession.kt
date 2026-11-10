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
            host == "googleapis.com" || host.endsWith(".googleapis.com")
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
