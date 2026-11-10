package com.vidgrab.app.feature.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vidgrab.app.core.common.LoginSite
import com.vidgrab.app.core.common.LoginSites
import com.vidgrab.app.core.designsystem.QtTopBar

/**
 * The site's own sign-in page in a WebView. VidGrab only observes cookies, so a password never reaches the app.
 * When the account cookie appears the screen closes itself; "Done" is there for flows where the cookie value
 * stays the same (switching back to the current account).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(siteId: String, switchAccount: Boolean = false, onClose: () -> Unit) {
    val site = remember(siteId) { LoginSites.byId(siteId) }
    if (site == null) {
        LaunchedEffect(siteId) { onClose() }
    } else {
        LoginContent(site, switchAccount, onClose)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginContent(site: LoginSite, switchAccount: Boolean, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    val baseline = remember(site, switchAccount) {
        if (switchAccount) SiteSessions.signedInCookie(site) else null
    }
    val start = remember(site, switchAccount) {
        if (switchAccount) site.switchAccountUrl ?: site.loginUrl else site.loginUrl
    }

    fun close() {
        if (finished) return
        finished = true
        runCatching { CookieManager.getInstance().flush() }
        SiteSessions.refresh(site)
        onClose()
    }

    fun check() {
        if (finished) return
        val account = SiteSessions.signedInCookie(site) ?: return
        if (switchAccount && account == baseline) return
        close()
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else close()
    }

    Column(Modifier.fillMaxSize()) {
        QtTopBar(
            title = if (switchAccount) "Switch ${site.name} account" else "Sign in to ${site.name}",
            onBack = ::close,
            subtitle = "${site.name} sign-in · VidGrab never sees your password",
        ) {
            TextButton(onClick = ::close) { Text("Done") }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setGeolocationEnabled(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            progress = 1
                            if (site.matches(url)) check()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            progress = 100
                            runCatching { CookieManager.getInstance().flush() }
                            if (site.matches(url)) check()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme
                            return scheme != "http" && scheme != "https"
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) progress = 100
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    loadUrl(start)
                    webView = this
                }
            },
        )
        Text(
            "Your sign-in happens on ${site.name}'s own page. Only the session cookies stay on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        runCatching { CookieManager.getInstance().flush() }
        SiteSessions.refresh(site)
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
            SiteSessions.refresh(site)
            webView?.run { stopLoading(); destroy() }
        }
    }
}
