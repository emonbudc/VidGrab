package com.vidgrab.app.feature.browser

import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import com.vidgrab.app.core.common.Format
import com.vidgrab.app.core.common.LoginSites
import com.vidgrab.app.core.designsystem.PillButton
import androidx.compose.foundation.background
import com.vidgrab.app.core.designsystem.pressable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.model.HistoryEntry
import com.vidgrab.app.core.model.Site
import com.vidgrab.app.core.playerui.findActivity
import com.vidgrab.app.core.playerui.PipState
import com.vidgrab.app.core.ui.brandFor
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private val YT_WATCH = Regex("""youtube\.com/(watch\?|shorts/)|youtu\.be/""")

/**
 * Reports every <video> on the page (playing state, size, duration, sources) plus the page title/image.
 * Also hooks play events so a video is reported the moment it starts.
 */
private const val SNIFF_JS = """
(function(){try{
 if(!window.__qtHooked){window.__qtHooked=true; window.__qtId=Math.random().toString(36).slice(2); window.__qtTouch=0;
  ['touchstart','pointerdown','click','keydown'].forEach(function(e){document.addEventListener(e,function(ev){window.__qtTouch=Date.now();
   // A tap on a video (or its controls overlay) means the user picked that video.
   var t=ev.touches&&ev.touches[0]?ev.touches[0]:ev; if(t.clientX==null||!window.__qtSeen)return;
   window.__qtSeen.forEach(function(v){var r=v.getBoundingClientRect();
    if(t.clientX>=r.left&&t.clientX<=r.right&&t.clientY>=r.top&&t.clientY<=r.bottom)v.__qtUser=true;});},true);});
  window.__qtSeen=new Set();
  function mark(v){try{window.__qtSeen.add(v);var ua=navigator.userActivation;
   if((ua&&ua.isActive)||Date.now()-window.__qtTouch<5000)v.__qtUser=true;}catch(x){}}
  document.addEventListener('play',function(e){mark(e.target);},true);
  // Players inside shadow DOM don't send events to the document; catch them when they call play().
  try{var op=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){mark(this);setTimeout(function(){window.__qtScan&&window.__qtScan();},300);return op.apply(this,arguments);};}catch(x){}
  ['play','playing','loadedmetadata'].forEach(function(e){document.addEventListener(e,function(){setTimeout(function(){window.__qtScan&&window.__qtScan();},250);},true);});
  setInterval(function(){window.__qtScan&&window.__qtScan();},2000);}
 window.__qtScan=function(){try{
  var vw=window.innerWidth||document.documentElement.clientWidth||0, vids=[], all=new Set();
  function walk(root){root.querySelectorAll('video').forEach(function(v){all.add(v);});
   root.querySelectorAll('*').forEach(function(el){if(el.shadowRoot)walk(el.shadowRoot);});}
  walk(document);
  if(window.__qtSeen)window.__qtSeen.forEach(function(v){if(v.isConnected&&v.tagName==='VIDEO')all.add(v);});
  all.forEach(function(v){ if(window.__qtSeen)window.__qtSeen.add(v);
   var r=v.getBoundingClientRect(), s=[];
   if(v.currentSrc)s.push(v.currentSrc); if(v.src)s.push(v.src);
   v.querySelectorAll('source').forEach(function(x){if(x.src)s.push(x.src);});
   vids.push({s:s,u:!!v.__qtUser,p:!v.paused&&!v.ended,t:v.currentTime||0,d:isFinite(v.duration)?v.duration:-1,m:!!v.muted,l:!!v.loop,w:r.width||0,vw:vw,po:v.poster||''});
  });
  function meta(p){var m=document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]');return m?m.content:null;}
  if(!vids.length&&window!==window.top)return;
  QtSniffer.report(JSON.stringify({f:window.__qtId,top:window===window.top,v:vids,t:meta('og:title')||document.title,i:meta('og:image')}));
 }catch(e){}};
 window.__qtScan();
}catch(e){}})();
"""

/**
 * For the invisible browser behind the share sheet: every video plays at volume 0 (nothing is heard) and counts
 * as started by the user, so the page's main video is detected exactly as when the user taps play.
 */
private const val SILENT_JS = """
(function(){try{
 if(window.__qtSilent)return; window.__qtSilent=true;
 function hush(v){try{v.__qtUser=true; if(v.volume!==0)v.volume=0;}catch(x){}}
 ['play','playing','volumechange','loadedmetadata'].forEach(function(e){document.addEventListener(e,function(ev){
  if(ev.target&&ev.target.tagName&&/^(VIDEO|AUDIO)$/.test(ev.target.tagName))hush(ev.target);},true);});
 try{var op=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){hush(this);return op.apply(this,arguments);};}catch(x){}
 document.querySelectorAll('video,audio').forEach(hush);
 // Nobody can tap play on an invisible page, so start the player like the user would (silently). Many players
 // (e.g. Vimeo's) only load the video once play is pressed.
 function kick(){try{
  var vs=document.querySelectorAll('video'), playing=false;
  vs.forEach(function(v){if(!v.paused&&!v.ended)playing=true;});
  if(playing)return;
  vs.forEach(function(v){hush(v);var p=v.play();if(p&&p.catch)p.catch(function(){});});
  var b=document.querySelector('[aria-label="Play"],[aria-label^="Play video"],[data-play-button],.vp-big-play-button,.vjs-big-play-button,.ytp-large-play-button,.jw-display-icon-display,.plyr__control--overlaid');
  if(b)b.click();
 }catch(x){}}
 setTimeout(kick,800); setInterval(kick,2500);
}catch(e){}})();
"""

/** Hands the rendered page (after scripts, with the user's session) to the page parser. */
private fun grabHtml(view: WebView, vm: BrowserViewModel) {
    val url = view.url ?: return
    view.evaluateJavascript("document.documentElement.outerHTML") { quoted ->
        val html = runCatching { org.json.JSONArray("[$quoted]").getString(0) }.getOrNull() ?: return@evaluateJavascript
        vm.onPageHtml(url, html)
    }
}

/**
 * Runs fetch() inside the page so requests carry the page's own cookies, origin and TLS session —
 * used for quality lists and playlists that refuse requests from outside the browser.
 */
private class PageFetcher {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()
    var webView: WebView? = null

    @JavascriptInterface
    fun fetchResult(id: String, text: String?) {
        pending.remove(id)?.complete(text)
    }

    suspend fun fetch(url: String): String? {
        val view = webView ?: return null
        val id = java.util.UUID.randomUUID().toString()
        val d = kotlinx.coroutines.CompletableDeferred<String?>()
        pending[id] = d
        val js = "fetch(" + org.json.JSONObject.quote(url) + ",{credentials:'include'}).then(function(r){return r.ok?r.text():null})" +
            ".then(function(t){QtFetch.fetchResult('" + id + "',t)}).catch(function(){QtFetch.fetchResult('" + id + "',null)});"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { view.evaluateJavascript(js, null) }
        return kotlinx.coroutines.withTimeoutOrNull(15_000) { d.await() }.also { pending.remove(id) }
    }
}

/** The app's own new-tab page, drawn by [BrowserHome] instead of a web page. */
const val BROWSER_HOME = "vidgrab://home"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    startUrl: String,
    autoDownload: Boolean = false,
    onClose: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSites: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onLogin: (siteId: String, switchAccount: Boolean) -> Unit = { _, _ -> },
    /** Invisible and muted (behind the share sheet): no sound, no history. */
    silent: Boolean = false,
    /** Separates browser states that open the same URL (each share gets a fresh one). */
    stateKey: String = startUrl,
    /** Shown as a bottom-bar destination: no Close button and the page stops above the floating bar. */
    isTab: Boolean = false,
) {
    val vm = koinViewModel<BrowserViewModel>(key = stateKey)
    val fetcher = remember { PageFetcher() }
    LaunchedEffect(Unit) { vm.autoDownload = autoDownload; vm.pageFetch = fetcher::fetch; vm.recordHistory = !silent }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val saved by vm.bookmarked.collectAsStateWithLifecycle()
    val sites by vm.sites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val loginStates by SiteSessions.states.collectAsStateWithLifecycle()
    val currentSite = remember(ui.url) { LoginSites.forUrl(ui.url) }
    LaunchedEffect(currentSite) { currentSite?.let(SiteSessions::refresh) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { SiteSessions.refresh() }
    val context = LocalContext.current
    val koinPlayer = koinInject<com.vidgrab.app.core.media.PlayerController>()
    val keyboard = LocalSoftwareKeyboardController.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    val startsAtHome = startUrl.isBlank() || startUrl == BROWSER_HOME
    var home by remember { mutableStateOf(startsAtHome && ui.url.isBlank()) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun navigateTo(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        editing = false
        keyboard?.hide()
        home = false
        webView?.onResume()
        webView?.loadUrl(if (Urls.looksLikeUrl(t)) Urls.normalize(t) else Urls.googleSearch(t))
    }

    fun goHome() {
        editing = false
        menu = false
        keyboard?.hide()
        home = true
        webView?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})", null)
        webView?.stopLoading()
        webView?.clearHistory()
        webView?.onPause()
        vm.onHome()
    }

    val isNative = YT_WATCH.containsMatchIn(ui.url)
    // Only when the user is on a video and it is playing — never on listing/home pages.
    // Instagram posts offer their photos too, so the button shows before (or without) any video playing.
    val canDownload = !home && (isNative || ui.mainVideo != null || ui.pageMedia != null ||
        com.vidgrab.app.core.extractor.InstagramReader.shortcode(ui.url) != null)
    val fallbackUrl = startUrl.takeUnless { it.isBlank() || it == BROWSER_HOME }.orEmpty()
    val shownUrl = if (home) "" else ui.url.ifBlank { fallbackUrl }.removePrefix("https://").removePrefix("http://")
    val secure = (ui.url.ifBlank { fallbackUrl }).startsWith("https")

    BackHandler {
        when {
            customView != null -> webView?.webChromeClient?.onHideCustomView()
            editing -> { editing = false; keyboard?.hide() }
            home -> onClose()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onClose()
        }
    }

    // Periodically re-scan the page for players that load lazily.
    LaunchedEffect(webView, ui.url, home) {
        while (!home) {
            delay(2500)
            if (silent) webView?.evaluateJavascript(SILENT_JS, null)
            webView?.evaluateJavascript(SNIFF_JS, null)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isTab) QtIconButton(QtIcons.Close, "Close", onClose)
                if (isTab && !home) {
                    QtIconButton(
                        QtIcons.ArrowBack, "Back", { webView?.goBack() },
                        enabled = ui.canGoBack, size = 40.dp, iconSize = 20.dp,
                    )
                    QtIconButton(
                        QtIcons.ArrowBack, "Forward", { webView?.goForward() },
                        modifier = Modifier.rotate(180f), enabled = ui.canGoForward, size = 40.dp, iconSize = 20.dp,
                    )
                } else if (!isTab && ui.canGoBack) {
                    QtIconButton(QtIcons.ArrowBack, "Back", { webView?.goBack() }, size = 40.dp, iconSize = 20.dp)
                }
                if (editing) {
                    TextField(
                        address, { address = it }, Modifier.weight(1f).focusRequester(focusRequester), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = { Text("Search or type URL") },
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (address.isBlank()) { editing = false; keyboard?.hide() } else navigateTo(address)
                        }),
                    )
                } else {
                    Row(
                        Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pressable(scaleDown = 0.99f, onClick = {
                                address = if (home) "" else ui.url.ifBlank { fallbackUrl }
                                editing = true
                            })
                            .padding(start = 12.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when {
                                home -> QtIcons.Search
                                secure -> QtIcons.Lock
                                else -> QtIcons.Public
                            },
                            null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (home) "Search or type URL" else shownUrl,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (home) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                        )
                        if (!home) QtIconButton(QtIcons.Refresh, "Reload", { webView?.reload() }, size = 34.dp, iconSize = 18.dp)
                    }
                }
                if (isTab && !home) QtIconButton(QtIcons.Home, "Home", { goHome() }, size = 40.dp, iconSize = 20.dp)
                Box {
                    QtIconButton(QtIcons.MoreVert, "More", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        if (!home) {
                            DropdownMenuItem(text = { Text(if (saved) "Saved" else "Save page") }, leadingIcon = {
                                Icon(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, null)
                            }, onClick = { vm.toggleBookmark(); menu = false })
                            DropdownMenuItem(text = { Text("Home") }, leadingIcon = { Icon(QtIcons.Home, null) },
                                onClick = { menu = false; goHome() })
                        } else {
                            DropdownMenuItem(text = { Text("Add shortcut") }, leadingIcon = { Icon(QtIcons.Add, null) },
                                onClick = { menu = false; onOpenSites() })
                        }
                        currentSite?.takeUnless { home }?.let { site ->
                            val signedIn = loginStates[site.id] == true
                            DropdownMenuItem(
                                text = { Text(if (signedIn) "Switch ${site.name} account" else "Sign in to ${site.name}") },
                                leadingIcon = { Icon(loginIcon(site), null) },
                                onClick = { menu = false; onLogin(site.id, signedIn) },
                            )
                        }
                        DropdownMenuItem(text = { Text("Accounts") }, leadingIcon = { Icon(QtIcons.Person, null) },
                            onClick = { menu = false; onOpenAccounts() })
                        DropdownMenuItem(text = { Text("Saved pages") }, leadingIcon = { Icon(QtIcons.Collections, null) },
                            onClick = { menu = false; onOpenBookmarks() })
                        DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(QtIcons.History, null) },
                            onClick = { menu = false; onOpenHistory() })
                        if (!home) {
                            DropdownMenuItem(text = { Text("Copy link") }, leadingIcon = { Icon(QtIcons.ContentCopy, null) }, onClick = {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", ui.url))
                                menu = false
                            })
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(QtIcons.Share, null) }, onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ui.url), "Share"))
                                menu = false
                            })
                            DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(QtIcons.OpenInNew, null) }, onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ui.url))) }
                                menu = false
                            })
                        }
                    }
                }
            }
            if (!home && ui.progress in 1..99) LinearProgressIndicator(progress = { ui.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            // Pages are remote web content: nothing local should be reachable, and https pages must
                            // not pull plain-http subresources.
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.setGeolocationEnabled(false)
                            // Ad scripts open pop-ups/pop-unders on any tap; only real links the user tapped may open.
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(true)
                            // Sign-ins (Facebook, Instagram…) live in the WebView's own cookie store; VidGrab never sees passwords.
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // Run the detector in every frame (embedded players live in iframes) from the very start.
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                WebViewCompat.addDocumentStartJavaScript(this, SNIFF_JS, setOf("*"))
                                if (silent) WebViewCompat.addDocumentStartJavaScript(this, SILENT_JS, setOf("*"))
                            }
                            vm.userAgent = settings.userAgentString
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun report(json: String) {
                                    val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
                                    val arr = o.optJSONArray("v") ?: org.json.JSONArray()
                                    val videos = (0 until arr.length()).map { i ->
                                        val v = arr.getJSONObject(i)
                                        val srcs = v.optJSONArray("s")
                                        PageVideo(
                                            sources = (0 until (srcs?.length() ?: 0)).map { srcs!!.getString(it) }.distinct(),
                                            userStarted = v.optBoolean("u"),
                                            playing = v.optBoolean("p"), currentTime = v.optDouble("t", 0.0), duration = v.optDouble("d", -1.0),
                                            muted = v.optBoolean("m"), loop = v.optBoolean("l"), width = v.optDouble("w", 0.0),
                                            viewportWidth = v.optDouble("vw", 0.0), poster = v.optString("po").ifBlank { null },
                                        )
                                    }
                                    val title = o.optString("t").ifBlank { null }
                                    val image = if (o.isNull("i")) null else o.optString("i").ifBlank { null }
                                    val frame = o.optString("f")
                                    val top = o.optBoolean("top", true)
                                    post { vm.onPageReport(frame, top, videos, if (top) title else null, if (top) image else null) }
                                }
                            }, "QtSniffer")
                            addJavascriptInterface(fetcher, "QtFetch")
                            fetcher.webView = this
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val u = request.url.toString()
                                    if (u.startsWith("http")) return false
                                    // The invisible page behind the share sheet must never open another app (e.g. Facebook's
                                    // "open in app" bounce); it stays on the web page.
                                    if (silent) {
                                        runCatching { Intent.parseUri(u, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                                            .getOrNull()?.takeIf { it.startsWith("http") }?.let(view::loadUrl)
                                        return true
                                    }
                                    val intent = runCatching {
                                        if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
                                    }.getOrNull() ?: return true
                                    // Sites try to bounce visitors into their app; stay in the browser unless the user tapped a link.
                                    val fallback = intent.getStringExtra("browser_fallback_url")
                                    if (!request.hasGesture()) {
                                        if (fallback != null) view.loadUrl(fallback)
                                        return true
                                    }
                                    runCatching { view.context.startActivity(intent) }.onFailure { if (fallback != null) view.loadUrl(fallback) }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    vm.onPageStarted(url)
                                    vm.onNavState(view.canGoBack(), view.canGoForward())
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    vm.onPageFinished(url, view.title)
                                    // The WebView writes cookies to disk only every ~30 s: a sign-in followed by closing the app
                                    // (swiped away, killed in the background) would be lost. Save them as soon as a page is in.
                                    CookieManager.getInstance().flush()
                                    if (silent) view.evaluateJavascript(SILENT_JS, null)
                                    view.evaluateJavascript(SNIFF_JS, null)
                                    grabHtml(view, vm)
                                    // Players often inject their config a moment after load.
                                    view.postDelayed({ if (view.url == url) grabHtml(view, vm) }, 2500)
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    vm.onUrlChanged(url, view.title)
                                    vm.onNavState(view.canGoBack(), view.canGoForward())
                                    view.postDelayed({ if (view.url == url) grabHtml(view, vm) }, 1500)
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val u = request.url.toString()
                                    val path = request.url.path.orEmpty().lowercase()
                                    if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".m4a") || path.endsWith(".mp3") ||
                                        path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".flv") || path.endsWith(".m3u8") ||
                                        u.contains("mime_type=video_mp4") || u.contains(".mp4?") || u.contains(".m3u8?")
                                    ) view.post { vm.onMediaRequest(u) }
                                    return null
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) = vm.onProgress(newProgress, view.canGoBack(), view.canGoForward())

                                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                    // target=_blank links the user tapped open here; script pop-ups are dropped.
                                    val hit = view.hitTestResult
                                    val link = if (isUserGesture && (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) hit.extra else null
                                    if (link != null && link.startsWith("http")) view.loadUrl(link)
                                    return false
                                }
                                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                    val decor = ctx.findActivity()?.window?.decorView as? FrameLayout ?: return
                                    decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                    customView = view
                                    // A page's full-screen video keeps playing in picture-in-picture when the user leaves the app.
                                    if (!silent && koinPlayer.prefs.autoPip) { PipState.ratio.value = 16f / 9f; PipState.wanted.value = true }
                                }
                                override fun onHideCustomView() {
                                    val decor = ctx.findActivity()?.window?.decorView as? FrameLayout ?: return
                                    customView?.let { decor.removeView(it) }
                                    customView = null
                                    PipState.wanted.value = false
                                }
                            }
                            // The invisible page behind the share sheet never offers files (an ad could trigger one).
                            if (!silent) setDownloadListener { url, ua, disposition, mime, _ ->
                                vm.downloadFile(url, mime, URLUtil.guessFileName(url, disposition, mime))
                            }
                            if (silent && WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(this, true)
                            if (!home) loadUrl(ui.url.ifBlank { fallbackUrl })
                            webView = this
                        }
                    },
                )
                if (home) BrowserHome(
                    sites = sites,
                    history = history,
                    onSearch = { address = ""; editing = true },
                    onOpen = { url -> navigateTo(url) },
                    onManage = onOpenSites,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Own bar under the page (never over it), so page elements and ad overlays can't steal the tap.
            AnimatedVisibility(canDownload, enter = expandVertically(), exit = shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val d = ui.pageMedia?.durationSec ?: ui.mainVideo?.duration?.takeIf { it > 0 }
                    Column(Modifier.weight(1f)) {
                        val instagramPost = ui.mainVideo == null && ui.pageMedia == null &&
                            com.vidgrab.app.core.extractor.InstagramReader.shortcode(ui.url) != null
                        Text(if (instagramPost) "Download this post" else "Video found on this page", style = MaterialTheme.typography.labelLarge)
                        Text(
                            listOfNotNull(d?.let { Format.duration(it.toLong()) }, Urls.host(ui.url)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    PillButton("Download", { vm.download(isNative) }, icon = QtIcons.Download)
                }
            }
            Spacer(if (isTab) Modifier.height(com.vidgrab.app.core.designsystem.LocalBottomBarHeight.current) else Modifier.navigationBarsPadding())
        }
    }

    // Leaving the browser or the app keeps the sessions too.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { CookieManager.getInstance().flush() }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            fetcher.webView = null
            vm.pageFetch = null
            webView?.run { stopLoading(); destroy() }
        }
    }
}

@Composable
private fun BrowserHome(
    sites: List<Site>,
    history: List<HistoryEntry>,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shortcuts = remember(sites, history) {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Pair<String, String>>()
        (history.map { it.title to it.url } + sites.filterNot { it.builtIn }.map { it.name to it.url })
            .filter { it.second.startsWith("http") }
            .forEach { (name, url) ->
                val key = Urls.host(url) ?: url
                if (seen.add(key)) out += (name.ifBlank { key }) to url
            }
        out.take(8)
    }
    Column(
        modifier.background(cs.background).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(54.dp))
        GoogleWordmark()
        Spacer(Modifier.height(24.dp))
        HomeSearchBar(onSearch)
        Spacer(Modifier.height(34.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shortcuts", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                "Manage", style = MaterialTheme.typography.labelLarge, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onManage)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (shortcuts.isEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                AddShortcutTile(onManage, Modifier.weight(1f))
                repeat(3) { Spacer(Modifier.weight(1f)) }
            }
            Text(
                "Your saved sites and recent pages show up here",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            shortcuts.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (label, url) -> Shortcut(label, url, onOpen, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun GoogleWordmark() {
    val letters = listOf(
        "G" to Color(0xFF4285F4), "o" to Color(0xFFEA4335), "o" to Color(0xFFFBBC05),
        "g" to Color(0xFF4285F4), "l" to Color(0xFF34A853), "e" to Color(0xFFEA4335),
    )
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        letters.forEach { (letter, color) ->
            Text(letter, color = color, fontSize = 42.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HomeSearchBar(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pressable(scaleDown = 0.99f, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QtIcons.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Search Google or type a URL", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
private fun Shortcut(label: String, url: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable { onOpen(url) }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShortcutIcon(label, url)
        Text(
            label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ShortcutIcon(label: String, url: String) {
    val cs = MaterialTheme.colorScheme
    val spec = brandFor(label.ifBlank { url }) ?: brandFor(url)
    val icon = spec?.icon
    val tint = spec?.tint ?: cs.onSurfaceVariant
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(spec?.background ?: SolidColor(cs.surfaceContainerHigh)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        else Text(
            label.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AddShortcutTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QtIcons.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Text("Add", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    }
}
