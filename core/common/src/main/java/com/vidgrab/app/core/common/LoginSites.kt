package com.vidgrab.app.core.common

/**
 * A site the user can sign in to inside VidGrab's browser. Credentials never pass through the app: the site's
 * own page runs in a WebView and only the resulting cookies are inspected.
 */
data class LoginSite(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val loginUrl: String,
    val switchAccountUrl: String? = null,
    val cookieUrls: List<String>,
    /** The cookie that only exists (with a real value) while an account is attached. */
    val signedInCookie: String,
    /** Cookies that belong to the sign-in and are removed on sign-out. */
    val authCookies: List<String>,
) {
    fun matches(url: String?): Boolean {
        val host = Urls.host(url)?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }
}

object LoginSites {
    val YouTube = LoginSite(
        id = "youtube",
        name = "YouTube",
        hosts = listOf("youtube.com", "youtu.be"),
        loginUrl = "https://m.youtube.com/signin",
        switchAccountUrl = "https://accounts.google.com/AccountChooser?uilel=0&service=youtube&passive=false&continue=http%3A%2F%2Fm.youtube.com%2Fsignin%3Fhl%3Den%26app%3Dm%26action_handle_signin%3Dtrue",
        cookieUrls = listOf(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com",
            "https://www.google.com",
            "https://accounts.google.com",
        ),
        signedInCookie = "LOGIN_INFO",
        authCookies = listOf(
            "LOGIN_INFO", "SID", "HSID", "SSID", "APISID", "SAPISID",
            "__Secure-1PSID", "__Secure-3PSID",
        ),
    )

    val Instagram = LoginSite(
        id = "instagram",
        name = "Instagram",
        hosts = listOf("instagram.com"),
        loginUrl = "https://www.instagram.com/accounts/login/",
        cookieUrls = listOf("https://www.instagram.com"),
        signedInCookie = "sessionid",
        authCookies = listOf("sessionid", "ds_user_id"),
    )

    val Facebook = LoginSite(
        id = "facebook",
        name = "Facebook",
        hosts = listOf("facebook.com", "fb.com", "fb.watch"),
        loginUrl = "https://m.facebook.com/login/",
        cookieUrls = listOf("https://www.facebook.com", "https://m.facebook.com"),
        signedInCookie = "c_user",
        authCookies = listOf("c_user", "xs", "datr", "sb"),
    )

    val TikTok = LoginSite(
        id = "tiktok",
        name = "TikTok",
        hosts = listOf("tiktok.com"),
        loginUrl = "https://www.tiktok.com/login",
        cookieUrls = listOf("https://www.tiktok.com"),
        signedInCookie = "sessionid",
        authCookies = listOf("sessionid", "sid_tt", "uid_tt"),
    )

    val all = listOf(YouTube, Instagram, Facebook, TikTok)

    fun byId(id: String): LoginSite? = all.firstOrNull { it.id == id }

    fun forUrl(url: String?): LoginSite? = all.firstOrNull { it.matches(url) }
}

fun parseCookieHeader(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    val cookies = LinkedHashMap<String, String>()
    for (part in header.split(';')) {
        val name = part.substringBefore('=', "").trim()
        if (name.isEmpty() || cookies.containsKey(name)) continue
        cookies[name] = part.substringAfter('=', "").trim()
    }
    return cookies
}

/** Short placeholder values (signed-out visits) do not count as an account. */
fun isSignedIn(site: LoginSite, cookieHeader: String?): Boolean =
    (parseCookieHeader(cookieHeader)[site.signedInCookie]?.length ?: 0) > 10
