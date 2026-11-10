package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.common.BrowserSession
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Bridges NewPipeExtractor's HTTP layer onto OkHttp. */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        // A signed-in in-app browser makes YouTube treat extraction as that account. BrowserSession only
        // answers for Google/YouTube hosts. NewPipe sends its own consent Cookie on every YouTube request,
        // so merge instead of replacing it or the sign-in cookies would be dropped.
        val session = BrowserSession.headers(request.url())
        val sessionCookie = session.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        if (sessionCookie != null) {
            val extractorCookie = request.headers().entries
                .filter { it.key.equals("Cookie", true) }
                .flatMap { it.value }
                .joinToString("; ")
                .takeIf { it.isNotBlank() }
            builder.header(
                "Cookie",
                if (extractorCookie == null) sessionCookie
                else BrowserSession.cookieHeader(extractorCookie, sessionCookie) ?: "$extractorCookie; $sessionCookie",
            )
        }
        val present = request.headers().keys.map { it.lowercase() }.toSet()
        session.forEach { (name, value) ->
            if (!name.equals("Cookie", true) && name.lowercase() !in present) builder.header(name, value)
        }
        BrowserSession.authHeaders(request.url()).forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val responseBody = response.body.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
