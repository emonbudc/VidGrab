package com.vidgrab.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.vidgrab.app.core.playerui.PipState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.vidgrab.app.core.common.Permissions
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.data.SettingsRepository
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.media.PlayerController
import com.vidgrab.app.core.model.AppSettings
import com.vidgrab.app.core.model.ThemeMode
import com.vidgrab.app.core.navigation.DownloadingRoute
import com.vidgrab.app.core.navigation.LibraryRoute
import com.vidgrab.app.core.navigation.PlayerRoute
import com.vidgrab.app.core.update.UpdateManager
import com.vidgrab.app.core.update.UpdatePrompt
import com.vidgrab.app.navigation.AppNavHost
import com.vidgrab.app.player.FloatingPlayerActivity
import com.vidgrab.app.core.navigation.VideoRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import com.vidgrab.app.navigation.UrlRouter
import com.vidgrab.app.ui.AppTheme
import com.vidgrab.app.ui.isDark
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settings: SettingsRepository by inject()
    private val router: UrlRouter by inject()
    private val sheet: DownloadSheetController by inject()
    private val player: PlayerController by inject()
    private val updates: UpdateManager by inject()
    private var pendingIntent by mutableStateOf<Intent?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestAllFilesAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissions()
        player.connect()
        // Look for a new GitHub release (throttled; release builds only unless the URL was overridden for testing).
        if (savedInstanceState == null) {
            updates.cleanUpInstalledUpdates()
            updates.consumeJustUpdated()?.let { v ->
                android.widget.Toast.makeText(this, "VidGrab updated to $v", android.widget.Toast.LENGTH_SHORT).show()
            }
            updates.checkOnLaunch(force = BuildConfig.UPDATE_URL_OVERRIDDEN)
        }
        if (savedInstanceState == null) pendingIntent = intent
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            val nav = rememberNavController()
            val dark = s.isDark()
            // Again after rotation too (full-screen video): it resets the bars' style.
            val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
            DisposableEffect(dark, orientation) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(s) {
                // Back on the first screen: leave like the home button would. Handled here (registered before the
                // screens' own handlers, so it only runs when none of them does), Android has no back gesture left to
                // animate with its shrinking "back to home" card.
                androidx.activity.compose.BackHandler { moveTaskToBack(true) }
                AppNavHost(nav)
                UpdatePrompt()
            }
            LaunchedEffect(pendingIntent) {
                val i = pendingIntent ?: return@LaunchedEffect
                pendingIntent = null
                // Expanded from the floating window: the video's page if it's still open, else the full player.
                if (i.getStringExtra("open") == "expand") {
                    val d = nav.currentDestination
                    if (d?.hasRoute(VideoRoute::class) != true && d?.hasRoute(PlayerRoute::class) != true) nav.navigate(PlayerRoute) { launchSingleTop = true }
                    return@LaunchedEffect
                }
                handleIntent(i) { route -> nav.navigate(route) { launchSingleTop = true } }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Leaving the app from a playing video (Settings → Player → Picture-in-picture): it goes on in the floating
        // window, a task of its own, so opening VidGrab again doesn't close it.
        if (PipState.wanted.value && !PipState.floating.value) FloatingPlayerActivity.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Left the app from a video without picture-in-picture: pause it unless Settings → Player → Background play is on.
        if (!isChangingConfigurations && !PipState.floating.value && PipState.videoOnScreen.value &&
            !player.state.value.audioOnly && !player.prefs.backgroundPlay) player.pause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent = intent
    }

    private fun handleIntent(intent: Intent, navigate: (Any) -> Unit) {
        intent.getStringExtra("open")?.let { open ->
            when {
                open == "downloads" -> navigate(DownloadingRoute)
                open == "player" -> navigate(PlayerRoute)
                open.startsWith("play:") -> navigate(LibraryRoute)
            }
            return
        }
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> Urls.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> null
        } ?: return
        // Shared text or the share popup's fallback means "download this"; a tapped YouTube link just opens it.
        val pasted = intent.action == Intent.ACTION_SEND || intent.getBooleanExtra(EXTRA_SHOW_SHEET, false)
        navigate(if (pasted) router.routeForPasted(url) else router.routeFor(url))
    }

    private fun requestStartupPermissions() {
        // Notifications (13+) and full storage access (10 and older) are requested together.
        val perms = (Permissions.notifications + Permissions.storageWrite + Permissions.legacyRead)
            .distinct()
            .filterNot { Permissions.granted(this, arrayOf(it)) }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray()) else requestAllFilesAccess()
    }

    /** Android 11+ keeps all-files access on a system page; ask once, right after the first-launch dialogs. */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30 || Permissions.allFilesAccess()) return
        val asked = getSharedPreferences("startup", MODE_PRIVATE)
        if (asked.getBoolean("all_files_asked", false)) return
        asked.edit().putBoolean("all_files_asked", true).apply()
        val appPage = Permissions.allFilesAccessIntent(this)
        runCatching { startActivity(appPage) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    companion object {
        const val EXTRA_SHOW_SHEET = "show_sheet"
    }
}
