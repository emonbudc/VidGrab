package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.common.BrowserSession
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.Page
import com.vidgrab.app.core.model.SearchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

internal data class YouTubeFeedCursor(val continuation: String)

internal data class YouTubeSearchCursor(val continuation: String)

internal data class YouTubeFeedConfig(
    val apiKey: String,
    val clientVersion: String,
    val visitorData: String?,
    val hl: String,
    val gl: String,
    val identityToken: String? = null,
    val sessionIndex: Int = 0,
)

/** YouTube's own signed-in home feed, search and related videos, as opposed to anonymous discovery. */
class YouTubeAccountFeed(
    private val client: OkHttpClient,
    private val language: String = Locale.getDefault().language.ifBlank { "en" },
) : AccountFeed {

    private val configLock = Mutex()

    @Volatile
    private var cachedConfig: Triple<Long, String, YouTubeFeedConfig>? = null

    override val isAvailable: Boolean
        get() = BrowserSession.authHeaders(ORIGIN).isNotEmpty()

    override fun owns(page: Any?): Boolean = page is YouTubeFeedCursor

    override fun ownsSearch(page: Any?): Boolean = page is YouTubeSearchCursor

    override suspend fun home(page: Any?): Page<MediaItem> = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext Page(emptyList(), null)
        val cursor = page as? YouTubeFeedCursor
        val config = config()
        val payload = buildJsonObject {
            put("context", context(config))
            if (cursor != null) put("continuation", cursor.continuation) else put("browseId", HOME_BROWSE_ID)
        }
        parseYouTubeHome(execute(apiRequest(BROWSE_URL, config, payload), "YouTube home feed"))
    }

    override suspend fun related(url: String): List<MediaItem>? = withContext(Dispatchers.IO) {
        val videoId = Urls.youtubeVideoId(url) ?: return@withContext null
        if (!isAvailable) return@withContext null
        val config = config()
        val payload = buildJsonObject {
            put("context", context(config))
            put("videoId", videoId)
        }
        parseYouTubeRelated(execute(apiRequest(NEXT_URL, config, payload), "YouTube related videos"), videoId)
    }

    override suspend fun search(query: String, filter: SearchFilter, page: Any?): Page<MediaItem>? =
        withContext(Dispatchers.IO) {
            if (!isAvailable) return@withContext null
            val cursor = page as? YouTubeSearchCursor
            if (page != null && cursor == null) return@withContext null
            if (cursor == null && query.isBlank()) return@withContext Page(emptyList(), null)
            val config = config()
            val payload = buildJsonObject {
                put("context", context(config))
                if (cursor != null) {
                    put("continuation", cursor.continuation)
                } else {
                    put("query", query)
                    put("params", searchParams(filter))
                }
            }
            parseYouTubeSearch(execute(apiRequest(SEARCH_URL, config, payload), "YouTube search"), filter)
        }

    private fun context(config: YouTubeFeedConfig): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", "WEB")
                put("clientVersion", config.clientVersion)
                put("hl", config.hl)
                put("gl", config.gl)
                put("userAgent", USER_AGENT)
                config.visitorData?.let { put("visitorData", it) }
            },
        )
    }

    private fun apiRequest(url: String, config: YouTubeFeedConfig, payload: JsonObject): Request =
        Request.Builder()
            .url("$url?prettyPrint=false&key=${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "${config.hl},en;q=0.8")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", config.clientVersion)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .apply {
                config.visitorData?.let { header("X-Goog-Visitor-Id", it) }
                config.identityToken?.let { header("X-Youtube-Identity-Token", it) }
                BrowserSession.headers(ORIGIN).entries
                    .firstOrNull { it.key.equals("Cookie", true) }
                    ?.value
                    ?.let { header("Cookie", it) }
                BrowserSession.authHeaders(ORIGIN).forEach { (name, value) -> header(name, value) }
                header("X-Goog-AuthUser", config.sessionIndex.toString())
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

    private fun execute(request: Request, label: String): JsonObject {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("$label returned HTTP ${response.code}")
            return Json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: error("$label returned an unexpected response")
        }
    }

    private fun searchParams(filter: SearchFilter): String = when (filter) {
        SearchFilter.ALL -> "8AEB"
        SearchFilter.VIDEOS -> "EgIQAfABAQ=="
        SearchFilter.PLAYLISTS -> "EgIQA_ABAQ=="
        SearchFilter.CHANNELS -> "EgIQAvABAQ=="
        SearchFilter.MUSIC -> "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    }

    private suspend fun config(): YouTubeFeedConfig {
        val session = BrowserSession.sessionKey(ORIGIN)
        cachedConfig?.let { (at, key, config) ->
            if (key == session && System.currentTimeMillis() - at < CONFIG_TTL_MS) return config
        }
        return configLock.withLock {
            cachedConfig?.let { (at, key, config) ->
                if (key == session && System.currentTimeMillis() - at < CONFIG_TTL_MS) return@withLock config
            }
            val defaults = YouTubeFeedConfig(
                apiKey = DEFAULT_API_KEY,
                clientVersion = DEFAULT_CLIENT_VERSION,
                visitorData = null,
                hl = language,
                gl = Locale.getDefault().country.ifBlank { "US" },
            )
            val fetchedAt = System.currentTimeMillis()
            val config = runCatching { fetchConfig(defaults) }.getOrDefault(defaults)
            cachedConfig = Triple(fetchedAt, session, config)
            config
        }
    }

    private fun fetchConfig(defaults: YouTubeFeedConfig): YouTubeFeedConfig {
        val request = Request.Builder()
            .url(HOME_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "${defaults.hl},en;q=0.8")
            .apply {
                BrowserSession.headers(ORIGIN).entries
                    .firstOrNull { it.key.equals("Cookie", true) }
                    ?.value
                    ?.let { header("Cookie", it) }
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return defaults
            return parseYouTubeConfig(response.body.string(), defaults) ?: defaults
        }
    }

    private companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val HOME_URL = "$ORIGIN/"
        const val BROWSE_URL = "$ORIGIN/youtubei/v1/browse"
        const val NEXT_URL = "$ORIGIN/youtubei/v1/next"
        const val SEARCH_URL = "$ORIGIN/youtubei/v1/search"
        const val HOME_BROWSE_ID = "FEwhat_to_watch"
        const val DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val DEFAULT_CLIENT_VERSION = "2.20261106.00.00"
        const val CONFIG_TTL_MS = 6 * 60 * 60 * 1000L
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

internal fun parseYouTubeConfig(html: String, defaults: YouTubeFeedConfig): YouTubeFeedConfig? {
    val version = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
        .find(html)?.groupValues?.get(1)
        ?: Regex(""""INNERTUBE_CONTEXT_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
        ?: return null
    val apiKey = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
        .find(html)?.groupValues?.get(1)
        ?: defaults.apiKey
    val visitor = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
        .find(html)?.groupValues?.get(1)
    val identity = Regex(""""ID_TOKEN"\s*:\s*"([^"]+)"""")
        .find(html)?.groupValues?.get(1)
    val sessionIndex = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
        .find(html)?.groupValues?.get(1)?.toIntOrNull()
    return defaults.copy(
        apiKey = apiKey,
        clientVersion = version,
        visitorData = visitor,
        identityToken = identity,
        sessionIndex = sessionIndex ?: defaults.sessionIndex,
    )
}

internal fun parseYouTubeHome(jsonText: String): Page<MediaItem> =
    runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()?.let { parseYouTubeHome(it) }
        ?: Page(emptyList(), null)

internal fun parseYouTubeHome(root: JsonElement): Page<MediaItem> {
    val items = LinkedHashMap<String, MediaItem>()

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                (element["videoRenderer"] as? JsonObject)?.let { renderer ->
                    parseVideoRenderer(renderer)?.let { items.putIfAbsent(it.url, it) }
                }
                (element["lockupViewModel"] as? JsonObject)?.let { renderer ->
                    parseLockup(renderer)?.let { items.putIfAbsent(it.url, it) }
                }
                (element["reelItemRenderer"] as? JsonObject)?.let { renderer ->
                    parseReel(renderer)?.let { items.putIfAbsent(it.url, it) }
                }
                (element["shortsLockupViewModel"] as? JsonObject)?.let { renderer ->
                    parseShortsLockup(renderer)?.let { items.putIfAbsent(it.url, it) }
                }
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }

    visit(root)
    val continuation = findContinuation(root)
    return Page(items.values.toList(), continuation?.let(::YouTubeFeedCursor))
}

internal fun parseYouTubeSearch(jsonText: String, filter: SearchFilter): Page<MediaItem> =
    runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()?.let { parseYouTubeSearch(it, filter) }
        ?: Page(emptyList(), null)

internal fun parseYouTubeSearch(root: JsonElement, filter: SearchFilter): Page<MediaItem> {
    val items = LinkedHashMap<String, MediaItem>()

    fun add(item: MediaItem?) {
        if (item == null || !item.matches(filter)) return
        items.putIfAbsent(item.url, item)
    }

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                (element["videoRenderer"] as? JsonObject)?.let(::parseVideoRenderer)?.let(::add)
                (element["compactVideoRenderer"] as? JsonObject)?.let(::parseVideoRenderer)?.let(::add)
                (element["channelRenderer"] as? JsonObject)?.let(::parseChannelRenderer)?.let(::add)
                (element["playlistRenderer"] as? JsonObject)?.let(::parsePlaylistRenderer)?.let(::add)
                (element["lockupViewModel"] as? JsonObject)?.let(::parseSearchLockup)?.let(::add)
                (element["reelItemRenderer"] as? JsonObject)?.let(::parseReel)?.let(::add)
                (element["shortsLockupViewModel"] as? JsonObject)?.let(::parseShortsLockup)?.let(::add)
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }

    visit(root)
    return Page(items.values.toList(), searchContinuation(root)?.let(::YouTubeSearchCursor))
}

internal fun parseYouTubeRelated(jsonText: String, videoId: String): List<MediaItem> =
    runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()?.let { parseYouTubeRelated(it, videoId) }
        ?: emptyList()

internal fun parseYouTubeRelated(root: JsonElement, videoId: String): List<MediaItem> {
    val items = LinkedHashMap<String, MediaItem>()
    val results = (root as? JsonObject)
        ?.obj("contents")
        ?.obj("twoColumnWatchNextResults")
        ?.obj("secondaryResults")
        ?.obj("secondaryResults")
        ?.arr("results")
    results?.asObjects()?.forEach { result ->
        val item = when {
            result["compactVideoRenderer"] is JsonObject -> parseVideoRenderer(result.obj("compactVideoRenderer")!!)
            result["videoRenderer"] is JsonObject -> parseVideoRenderer(result.obj("videoRenderer")!!)
            result["lockupViewModel"] is JsonObject -> parseLockup(result.obj("lockupViewModel")!!)
            else -> null
        }
        if (item != null && item.kind == ItemKind.VIDEO && Urls.youtubeVideoId(item.url) != videoId) {
            items.putIfAbsent(item.url, item)
        }
    }
    if (items.isEmpty()) {
        parseYouTubeHome(root).items.forEach { item ->
            if (item.kind == ItemKind.VIDEO && Urls.youtubeVideoId(item.url) != videoId) {
                items.putIfAbsent(item.url, item)
            }
        }
    }
    return items.values.toList()
}

private fun MediaItem.matches(filter: SearchFilter): Boolean = when (filter) {
    SearchFilter.ALL -> true
    SearchFilter.VIDEOS, SearchFilter.MUSIC -> kind == ItemKind.VIDEO
    SearchFilter.PLAYLISTS -> kind == ItemKind.PLAYLIST
    SearchFilter.CHANNELS -> kind == ItemKind.CHANNEL
}

private fun searchContinuation(root: JsonElement): String? {
    val sections = (root as? JsonObject)
        ?.obj("contents")
        ?.obj("twoColumnSearchResultsRenderer")
        ?.obj("primaryContents")
        ?.obj("sectionListRenderer")
        ?.arr("contents")
    val token = sections?.asObjects()
        ?.lastOrNull { it.containsKey("continuationItemRenderer") || it.containsKey("continuationItemViewModel") }
        ?.let(::findContinuationToken)
    return token ?: findContinuation(root)
}

private fun parseChannelRenderer(obj: JsonObject): MediaItem? {
    val id = obj.str("channelId") ?: return null
    val title = textOf(obj["title"]) ?: return null
    val endpoint = obj.obj("navigationEndpoint")
    val url = endpoint?.obj("browseEndpoint")?.let(::endpointChannelUrl)
        ?: endpoint?.obj("commandMetadata")?.obj("webCommandMetadata")?.str("url")?.let(::absolute)
        ?: "$ORIGIN/channel/$id"
    val handle = textOf(obj["subscriberCountText"])
    val withHandle = handle?.startsWith("@") == true
    val subscriberText = if (withHandle) {
        textOf(obj["videoCountText"])
    } else {
        handle ?: textOf(obj["videoCountText"])
    }
    return MediaItem(
        url = url,
        title = title,
        thumbnail = bestThumbnail(obj["thumbnail"]),
        kind = ItemKind.CHANNEL,
        subscriberCount = parseCount(subscriberText),
        streamCount = if (withHandle) -1 else parseCount(textOf(obj["videoCountText"])),
        description = textOf(obj["descriptionSnippet"]),
    )
}

private fun parsePlaylistRenderer(obj: JsonObject): MediaItem? {
    val id = obj.str("playlistId") ?: return null
    val title = textOf(obj["title"]) ?: return null
    val byline = obj.obj("shortBylineText") ?: obj.obj("longBylineText")
    return MediaItem(
        url = "$ORIGIN/playlist?list=$id",
        title = title,
        thumbnail = bestPlaylistThumbnail(obj),
        uploader = textOf(byline).orEmpty(),
        uploaderUrl = channelUrl(byline),
        streamCount = parseCount(textOf(obj["videoCountText"])),
        kind = ItemKind.PLAYLIST,
    )
}

private fun parseSearchLockup(obj: JsonObject): MediaItem? {
    val type = obj.str("contentType").orEmpty()
    return if (type.contains("PLAYLIST", true) || type.contains("PODCAST", true)) {
        parsePlaylistLockup(obj)
    } else {
        parseLockup(obj)
    }
}

private fun parsePlaylistLockup(obj: JsonObject): MediaItem? {
    val id = obj.str("contentId") ?: return null
    val meta = obj.obj("metadata")?.obj("lockupMetadataViewModel") ?: return null
    val title = meta.obj("title")?.str("content") ?: return null
    val rows = meta.obj("metadata")?.obj("contentMetadataViewModel")?.arr("metadataRows")
    val firstParts = rows?.getOrNull(0)?.asObject()?.arr("metadataParts")
    val uploader = firstParts?.firstOrNull()?.asObject()?.obj("text")?.str("content").orEmpty()
    val image = obj.obj("contentImage")
        ?.obj("collectionThumbnailViewModel")
        ?.obj("primaryThumbnail")
        ?.obj("thumbnailViewModel")
        ?.obj("image")
        ?: obj.obj("contentImage")?.obj("thumbnailViewModel")?.obj("image")
    return MediaItem(
        url = "$ORIGIN/playlist?list=$id",
        title = title,
        thumbnail = bestThumbnail(image),
        uploader = uploader,
        uploaderUrl = findChannelUrl(meta),
        streamCount = lockupBadgeCount(obj.obj("contentImage")),
        kind = ItemKind.PLAYLIST,
    )
}

private fun parseVideoRenderer(obj: JsonObject): MediaItem? {
    val id = obj.str("videoId") ?: return null
    val title = textOf(obj["title"]) ?: return null
    val owner = obj.obj("ownerText") ?: obj.obj("longBylineText") ?: obj.obj("shortBylineText")
    val path = obj.obj("navigationEndpoint")
        ?.obj("commandMetadata")
        ?.obj("webCommandMetadata")
        ?.str("url")
    val short = path?.contains("/shorts/") == true
    val live = isLive(obj)
    return MediaItem(
        url = videoUrl(id, short),
        title = title,
        thumbnail = bestThumbnail(obj["thumbnail"]) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        uploader = textOf(owner).orEmpty(),
        uploaderUrl = channelUrl(owner),
        uploaderAvatar = obj.obj("channelThumbnailSupportedRenderers")
            ?.obj("channelThumbnailWithLinkRenderer")
            ?.obj("thumbnail")
            ?.let(::bestThumbnail),
        durationSec = parseDuration(textOf(obj["lengthText"]) ?: overlayTime(obj["thumbnailOverlays"])),
        viewCount = parseCount(textOf(obj["viewCountText"])),
        uploadDate = textOf(obj["publishedTimeText"]),
        isLive = live,
        isShort = short,
        kind = ItemKind.VIDEO,
    )
}

private fun parseLockup(obj: JsonObject): MediaItem? {
    val id = obj.str("contentId") ?: return null
    val type = obj.str("contentType").orEmpty()
    if (type.isNotBlank() && !type.contains("VIDEO", true) && !type.contains("SHORT", true)) return null
    val meta = obj.obj("metadata")?.obj("lockupMetadataViewModel") ?: return null
    val title = meta.obj("title")?.str("content") ?: return null
    val rows = meta.obj("metadata")?.obj("contentMetadataViewModel")?.arr("metadataRows")
    val firstParts = rows?.getOrNull(0)?.asObject()?.arr("metadataParts")
    val stats = rows?.getOrNull(1)?.asObject()?.arr("metadataParts")
    val uploader = firstParts?.firstOrNull()?.asObject()?.obj("text")?.str("content").orEmpty()
    val viewText = stats?.firstOrNull()?.asObject()?.obj("text")?.str("content")
    val published = stats?.getOrNull(1)?.asObject()?.obj("text")?.str("content")
    val image = obj.obj("contentImage")?.obj("thumbnailViewModel")?.obj("image")
    val path = obj.obj("rendererContext")
        ?.obj("commandContext")
        ?.obj("onTap")
        ?.obj("innertubeCommand")
        ?.obj("commandMetadata")
        ?.obj("webCommandMetadata")
        ?.str("url")
    val short = type.contains("SHORT", true) || path?.contains("/shorts/") == true
    val avatar = meta.obj("image")
        ?.obj("decoratedAvatarViewModel")
        ?.obj("avatar")
        ?.obj("avatarViewModel")
        ?.obj("image")
        ?.let(::bestThumbnail)
    return MediaItem(
        url = videoUrl(id, short),
        title = title,
        thumbnail = bestThumbnail(image) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        uploader = uploader,
        uploaderUrl = channelUrl(meta.obj("image")) ?: findChannelUrl(meta) ?: findChannelUrl(obj),
        uploaderAvatar = avatar,
        durationSec = lockupDuration(obj.obj("contentImage")),
        viewCount = parseCount(viewText),
        uploadDate = published,
        isLive = containsLive(obj.obj("contentImage")),
        isShort = short,
        kind = ItemKind.VIDEO,
    )
}

private fun parseReel(obj: JsonObject): MediaItem? {
    val id = obj.str("videoId") ?: return null
    val title = textOf(obj["headline"]) ?: textOf(obj["title"]) ?: return null
    return MediaItem(
        url = "$ORIGIN/shorts/$id",
        title = title,
        thumbnail = bestThumbnail(obj["thumbnail"]),
        uploader = textOf(obj.obj("ownerText")).orEmpty(),
        uploaderUrl = channelUrl(obj.obj("ownerText")),
        durationSec = parseDuration(textOf(obj["lengthText"])),
        viewCount = parseCount(textOf(obj["viewCountText"])),
        isLive = isLive(obj),
        isShort = true,
        kind = ItemKind.VIDEO,
    )
}

private fun parseShortsLockup(obj: JsonObject): MediaItem? {
    val id = findReelVideoId(obj) ?: return null
    val title = (obj["overlayMetadata"] as? JsonObject)
        ?.obj("primaryText")
        ?.str("content")
        ?: textOf(obj["headline"])
        ?: return null
    val thumb = obj["thumbnail"]?.let(::bestThumbnail)
        ?: obj.obj("thumbnailViewModel")?.obj("image")?.let(::bestThumbnail)
    return MediaItem(
        url = "$ORIGIN/shorts/$id",
        title = title,
        thumbnail = thumb,
        viewCount = parseCount(
            (obj["overlayMetadata"] as? JsonObject)?.obj("secondaryText")?.str("content"),
        ),
        isShort = true,
        kind = ItemKind.VIDEO,
    )
}

private fun videoUrl(id: String, short: Boolean): String =
    if (short) "$ORIGIN/shorts/$id" else "$ORIGIN/watch?v=$id"

private fun textOf(element: JsonElement?): String? {
    if (element == null) return null
    val obj = element as? JsonObject ?: return (element as? JsonPrimitive)?.contentOrNull
    obj.str("simpleText")?.let { return it }
    obj.str("content")?.let { return it }
    val runs = obj.arr("runs") ?: return null
    return runs.mapNotNull { run -> (run as? JsonObject)?.str("text") }
        .joinToString("")
        .ifBlank { null }
}

private fun bestThumbnail(element: JsonElement?): String? {
    val obj = element as? JsonObject ?: return null
    val list = obj.arr("thumbnails") ?: obj.arr("sources") ?: return null
    val best = list.asObjects().maxByOrNull { thumbnail ->
        (thumbnail.int("width") ?: 0) * (thumbnail.int("height") ?: 0)
    }
    return normalizeThumbnail(best?.str("url") ?: list.asObjects().lastOrNull()?.str("url"))
}

private fun bestPlaylistThumbnail(obj: JsonObject): String? {
    bestThumbnail(obj["thumbnail"])?.let { return it }
    val groups = obj.arr("thumbnails") ?: return null
    val best = groups.asObjects()
        .flatMap { group -> group.arr("thumbnails")?.asObjects().orEmpty() }
        .maxByOrNull { (it.int("width") ?: 0) * (it.int("height") ?: 0) }
    return normalizeThumbnail(best?.str("url"))
}

private fun normalizeThumbnail(url: String?): String? = when {
    url == null -> null
    url.startsWith("//") -> "https:$url"
    url.startsWith("http") -> url
    else -> absolute(url)
}

private fun channelUrl(element: JsonObject?): String? = element
    ?.arr("runs")
    ?.firstOrNull()
    ?.asObject()
    ?.obj("navigationEndpoint")
    ?.obj("browseEndpoint")
    ?.let(::endpointChannelUrl)

private fun endpointChannelUrl(endpoint: JsonObject): String? {
    endpoint.str("canonicalBaseUrl")?.let { return absolute(it) }
    endpoint.str("browseId")?.let { return "$ORIGIN/channel/$it" }
    return null
}

private fun findChannelUrl(element: JsonElement?): String? {
    when (element) {
        is JsonObject -> {
            (element["browseEndpoint"] as? JsonObject)?.let(::endpointChannelUrl)?.let { return it }
            element.values.forEach { findChannelUrl(it)?.let { url -> return url } }
        }
        is JsonArray -> element.forEach { findChannelUrl(it)?.let { url -> return url } }
        else -> Unit
    }
    return null
}

private fun absolute(path: String): String = when {
    path.startsWith("http") -> path
    path.startsWith("/") -> ORIGIN + path
    else -> "$ORIGIN/$path"
}

private fun findReelVideoId(element: JsonElement?): String? {
    when (element) {
        is JsonObject -> {
            (element["reelWatchEndpoint"] as? JsonObject)?.str("videoId")?.let { return it }
            element.values.forEach { findReelVideoId(it)?.let { id -> return id } }
        }
        is JsonArray -> element.forEach { findReelVideoId(it)?.let { id -> return id } }
        else -> Unit
    }
    return null
}

private fun overlayTime(element: JsonElement?): String? {
    when (element) {
        is JsonObject -> {
            (element["thumbnailOverlayTimeStatusRenderer"] as? JsonObject)?.let { renderer ->
                textOf(renderer["text"])?.let { return it }
            }
            element.values.forEach { overlayTime(it)?.let { time -> return time } }
        }
        is JsonArray -> element.forEach { overlayTime(it)?.let { time -> return time } }
        else -> Unit
    }
    return null
}

private fun isLive(obj: JsonObject): Boolean {
    val overlay = obj.obj("thumbnailOverlays")?.let(::containsLive) == true
    return overlay || containsLive(obj["badges"])
}

private fun containsLive(element: JsonElement?): Boolean {
    when (element) {
        is JsonObject -> {
            for (key in LIVE_KEYS) {
                val value = (element[key] as? JsonPrimitive)?.contentOrNull ?: continue
                if (value.equals("LIVE", true) || value.contains("LIVE NOW", true)) return true
            }
            element.values.forEach { if (containsLive(it)) return true }
        }
        is JsonArray -> element.forEach { if (containsLive(it)) return true }
        else -> Unit
    }
    return false
}

private fun lockupDuration(element: JsonElement?): Long {
    var result = -1L
    fun visit(value: JsonElement?) {
        when (value) {
            is JsonObject -> {
                (value["thumbnailBadgeViewModel"] as? JsonObject)?.str("text")?.let { text ->
                    val parsed = parseDuration(text)
                    if (parsed >= 0) result = parsed
                }
                value.values.forEach(::visit)
            }
            is JsonArray -> value.forEach(::visit)
            else -> Unit
        }
    }
    visit(element)
    return result
}

private fun lockupBadgeCount(element: JsonElement?): Long {
    var result = -1L
    fun visit(value: JsonElement?) {
        when (value) {
            is JsonObject -> {
                (value["thumbnailBadgeViewModel"] as? JsonObject)?.str("text")?.let { text ->
                    val parsed = parseCount(text)
                    if (parsed >= 0) result = parsed
                }
                value.values.forEach(::visit)
            }
            is JsonArray -> value.forEach(::visit)
            else -> Unit
        }
    }
    visit(element)
    return result
}

private fun parseDuration(text: String?): Long {
    val value = text?.trim().orEmpty()
    if (value.isEmpty() || value.equals("LIVE", true)) return -1
    val parts = value.split(':')
    if (parts.size !in 2..3) return -1
    val numbers = parts.map { part -> part.toLongOrNull() ?: return -1 }
    return if (numbers.size == 2) {
        numbers[0] * 60 + numbers[1]
    } else {
        numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
}

private fun parseCount(text: String?): Long {
    if (text == null) return -1
    if (text.contains("No views", true)) return 0
    val match = COUNT.find(text) ?: return -1
    val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
    val multiplier = when (match.groupValues[2].uppercase()) {
        "K" -> 1_000.0
        "M" -> 1_000_000.0
        "B" -> 1_000_000_000.0
        else -> 1.0
    }
    return (number * multiplier).toLong()
}

private fun findContinuation(root: JsonElement): String? {
    // Home can contain shelf continuations as well as the feed's own; the last one in document
    // order is the grid continuation used for the next page.
    var token: String? = null
    fun scan(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                if (element.containsKey("continuationItemRenderer") ||
                    element.containsKey("continuationItemViewModel")
                ) {
                    findContinuationToken(element)?.let { token = it }
                }
                element.values.forEach(::scan)
            }
            is JsonArray -> element.forEach(::scan)
            else -> Unit
        }
    }
    scan(root)
    return token ?: findContinuationToken(root)
}

private fun findContinuationToken(element: JsonElement?): String? {
    when (element) {
        is JsonObject -> {
            (element["continuationCommand"] as? JsonObject)?.str("token")?.let { return it }
            (element["nextContinuationData"] as? JsonObject)?.str("continuation")?.let { return it }
            (element["reloadContinuationData"] as? JsonObject)?.str("continuation")?.let { return it }
            element.values.forEach { findContinuationToken(it)?.let { token -> return token } }
        }
        is JsonArray -> element.forEach { findContinuationToken(it)?.let { token -> return token } }
        else -> Unit
    }
    return null
}

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

private fun JsonArray.asObjects(): List<JsonObject> = mapNotNull { it as? JsonObject }

private val COUNT = Regex("""([\d.,]+)\s*([KMB])?""", RegexOption.IGNORE_CASE)
private val LIVE_KEYS = listOf("label", "badgeText", "text", "style", "accessibilityLabel")
private const val ORIGIN = "https://www.youtube.com"
