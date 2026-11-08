package com.vidgrab.app.navigation

import com.vidgrab.app.core.designsystem.QtTransitions
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.vidgrab.app.core.data.LibraryRepository
import com.vidgrab.app.core.downloadsheet.DownloadSheetHost
import com.vidgrab.app.core.model.DownloadStatus
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.navigation.*
import com.vidgrab.app.feature.browser.BookmarksScreen
import com.vidgrab.app.feature.browser.BrowserScreen
import com.vidgrab.app.feature.browser.HistoryScreen
import com.vidgrab.app.feature.browser.SitesScreen
import com.vidgrab.app.feature.home.HomeActions
import com.vidgrab.app.player.GalleryPlayerActivity
import com.vidgrab.app.player.FloatingPlayerActivity
import com.vidgrab.app.core.designsystem.LocalBottomBarHidden
import com.vidgrab.app.feature.settings.AboutScreen
import com.vidgrab.app.feature.settings.DownloadSettingsScreen
import com.vidgrab.app.feature.settings.NotificationSettingsScreen
import com.vidgrab.app.feature.settings.PlayerSettingsScreen
import com.vidgrab.app.feature.settings.SettingsActions
import com.vidgrab.app.feature.settings.SettingsScreen
import com.vidgrab.app.feature.settings.ThemeSettingsScreen
import com.vidgrab.app.feature.tools.DeletedHistoryScreen
import com.vidgrab.app.feature.tools.RecycleBinScreen
import com.vidgrab.app.feature.tools.StatusSaverScreen
import com.vidgrab.app.feature.tools.VaultScreen
import com.vidgrab.app.feature.home.HomeScreen
import com.vidgrab.app.feature.home.HowToScreen
import com.vidgrab.app.feature.library.DownloadingScreen
import com.vidgrab.app.feature.library.LibraryScreen
import com.vidgrab.app.feature.player.MiniPlayer
import com.vidgrab.app.feature.player.PlayerScreen
import com.vidgrab.app.feature.search.SearchResultsScreen
import com.vidgrab.app.feature.search.SearchScreen
import com.vidgrab.app.feature.video.ListPageScreen
import com.vidgrab.app.feature.video.SavedVideosScreen
import com.vidgrab.app.feature.video.VideoScreen
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

private val tabs = listOf(
    NavTab(HomeRoute, "Home", QtIcons.Home, QtIcons.HomeFilled),
    NavTab(LibraryRoute, "Downloads", QtIcons.Download, QtIcons.DownloadDone),
    NavTab(BrowserTabRoute, "Browser", QtIcons.Public, QtIcons.Explore),
    NavTab(SettingsRoute, "Settings", QtIcons.Settings, QtIcons.SettingsFilled),
)

private const val BROWSER_HOME = "https://m.youtube.com/"

private fun tabIndexFor(dest: androidx.navigation.NavDestination?): Int = when {
    dest == null -> -1
    dest.hierarchy.any { it.hasRoute(HomeRoute::class) } -> 0
    dest.hierarchy.any {
        it.hasRoute(LibraryRoute::class) || it.hasRoute(DownloadingRoute::class)
    } -> 1
    dest.hierarchy.any { it.hasRoute(BrowserTabRoute::class) } -> 2
    dest.hierarchy.any { it.hasRoute(SettingsRoute::class) } -> 3
    else -> -1
}

@Composable
fun AppNavHost(nav: NavHostController) {
    val router = koinInject<UrlRouter>()
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = koinInject<LibraryRepository>()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val selectedTab = tabIndexFor(dest)
    val onTab = selectedTab >= 0
    // A gallery video is already on screen (its own player or picture-in-picture); gallery audio reopens that player.
    val player = koinInject<com.vidgrab.app.core.media.PlayerController>()
    val playing by player.state.collectAsStateWithLifecycle()
    val galleryId = playing.current?.id?.takeIf { it.startsWith("gallery:") }?.removePrefix("gallery:")?.toLongOrNull()
    val galleryVideo = galleryId != null && !playing.audioOnly
    // The floating window is showing the video instead.
    val floating by com.vidgrab.app.core.playerui.PipState.floating.collectAsStateWithLifecycle()
    val showMini = !galleryVideo && !floating && dest?.let { d -> !d.hasRoute(PlayerRoute::class) && !d.hasRoute(VideoRoute::class) } ?: false
    // A tab screen can swap the floating bar for its own (the gallery's selection actions).
    val barHidden = remember { mutableStateOf(false) }
    // The mini player only draws when something is loaded; nothing playing = no space kept for it.
    val miniVisible = showMini && !barHidden.value && playing.current != null
    val activeCount by remember(library) {
        library.downloads.map { l -> l.count { it.status != DownloadStatus.COMPLETED && it.deletedAt == 0L } }
    }.collectAsStateWithLifecycle(0)

    fun openUrl(url: String) = nav.navigate(router.routeFor(url))
    fun downloadUrl(url: String) = nav.navigate(router.routeForPasted(url))
    fun openItem(item: MediaItem) = nav.navigate(
        when (item.kind) {
            ItemKind.VIDEO -> VideoRoute(item.url, item.title.ifBlank { null }, item.thumbnail)
            ItemKind.CHANNEL -> ChannelRoute(item.url)
            ItemKind.PLAYLIST -> PlaylistRoute(item.url)
        },
    )

    val analytics = koinInject<com.vidgrab.app.core.analytics.Analytics>()
    // Screen views by route type only (e.g. "VideoRoute"), never the URL inside it.
    androidx.compose.runtime.LaunchedEffect(dest?.route) {
        dest?.route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')?.let(analytics::screen)
    }
    val density = LocalDensity.current
    var overlayHeight by remember { mutableStateOf(0.dp) }
    // Surface (not a plain Box) so screens get the theme's content colour, like Scaffold provided before.
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize()) {
        // Tab screens draw behind the floating bar (and see their content through the gaps around it);
        // other screens simply stop above the mini player.
        CompositionLocalProvider(LocalBottomBarHeight provides if (onTab) overlayHeight else 0.dp, LocalBottomBarHidden provides barHidden) {
        NavHost(
            nav, startDestination = HomeRoute,
            // Above the mini player on other screens; that space already covers the navigation bar, so screens' own
            // navigation-bar padding is marked as used (else they'd leave an empty strip on top of it).
            modifier = Modifier.fillMaxSize().padding(bottom = if (onTab) 0.dp else overlayHeight)
                .consumeWindowInsets(PaddingValues(bottom = if (onTab) 0.dp else overlayHeight)),
            enterTransition = QtTransitions.push,
            exitTransition = QtTransitions.pushExit,
            popEnterTransition = QtTransitions.popEnter,
            popExitTransition = QtTransitions.popExit,
            // No predictive back. Android 16 ignores the app's opt-out for apps targeting it, so the gesture's progress
            // must not drive any animation here: the screen stays still while the finger moves, then simply goes back.
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { ExitTransition.None },
        ) {
            composable<HomeRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                HomeScreen(
                    HomeActions(
                        openSearch = { nav.navigate(SearchRoute(it)) },
                        openSearchResults = { nav.navigate(SearchResultsRoute(it)) },
                        downloadUrl = ::downloadUrl,
                        openItem = ::openItem,
                        openChannel = { nav.navigate(ChannelRoute(it)) },
                        openHowTo = { nav.navigate(HowToRoute(it)) },
                        openSites = { nav.navigate(SitesRoute) },
                        openSettings = {
                            nav.navigate(SettingsRoute) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        openLibrary = {
                            nav.navigate(LibraryRoute) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        openVault = { nav.navigate(VaultRoute) },
                    ),
                )
            }
            composable<LibraryRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                LibraryScreen(
                    onOpenDownloading = { nav.navigate(DownloadingRoute) },
                    onOpenPlayer = { nav.navigate(PlayerRoute) { launchSingleTop = true } },
                    onOpenSource = ::openUrl,
                    onOpenSaved = { nav.navigate(SavedVideosRoute) },
                )
            }
            composable<SettingsRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                SettingsScreen(
                    SettingsActions(
                        downloadSettings = { nav.navigate(DownloadSettingsRoute) },
                        playerSettings = { nav.navigate(PlayerSettingsRoute) },
                        notificationSettings = { nav.navigate(NotificationSettingsRoute) },
                        theme = { nav.navigate(ThemeSettingsRoute) },
                        recycleBin = { nav.navigate(RecycleBinRoute) },
                        deletedHistory = { nav.navigate(DeletedHistoryRoute) },
                        statusSaver = { nav.navigate(StatusSaverRoute) },
                        vault = { nav.navigate(VaultRoute) },
                        about = { nav.navigate(AboutRoute) },
                    ),
                )
            }
            composable<DownloadSettingsRoute> { DownloadSettingsScreen(nav::popBackStack) }
            composable<PlayerSettingsRoute> { PlayerSettingsScreen(nav::popBackStack) }
            composable<NotificationSettingsRoute> { NotificationSettingsScreen(nav::popBackStack) }
            composable<ThemeSettingsRoute> { ThemeSettingsScreen(nav::popBackStack) }
            composable<AboutRoute> { AboutScreen(nav::popBackStack) }
            composable<RecycleBinRoute> { RecycleBinScreen(nav::popBackStack, onHistory = { nav.navigate(DeletedHistoryRoute) }) }
            composable<DeletedHistoryRoute> { DeletedHistoryScreen(nav::popBackStack) }
            composable<VaultRoute> { VaultScreen(nav::popBackStack, onOpenPlayer = { nav.navigate(PlayerRoute) { launchSingleTop = true } }) }
            composable<SearchRoute> { e ->
                SearchScreen(
                    e.toRoute<SearchRoute>().initial,
                    onBack = nav::popBackStack,
                    onSearch = { q -> nav.navigate(SearchResultsRoute(q)) { popUpTo<SearchRoute> { inclusive = true } } },
                    onOpenUrl = { u -> nav.navigate(router.routeFor(u)) { popUpTo<SearchRoute> { inclusive = true } } },
                    onViewSites = { nav.navigate(SitesRoute) },
                    onDownloadUrl = { u -> nav.navigate(router.routeForPasted(u)) { popUpTo<SearchRoute> { inclusive = true } } },
                )
            }
            composable<SearchResultsRoute> { e ->
                val q = e.toRoute<SearchResultsRoute>().query
                SearchResultsScreen(
                    q, onBack = nav::popBackStack,
                    onEditQuery = { nav.navigate(SearchRoute(q)) },
                    onOpen = ::openItem, onOpenUrl = ::openUrl,
                )
            }
            composable<VideoRoute> { e ->
                val r = e.toRoute<VideoRoute>()
                VideoScreen(r.url, previewTitle = r.title, previewThumbnail = r.thumbnail, onBack = nav::popBackStack, onOpen = ::openItem,
                    onChannel = { nav.navigate(ChannelRoute(it)) },
                    onPip = { FloatingPlayerActivity.start(context) },
                    onPlayNext = { item -> nav.navigate(VideoRoute(item.url, item.title.ifBlank { null }, item.thumbnail)) { popUpTo<VideoRoute> { inclusive = true } } })
            }
            composable<SavedVideosRoute> { SavedVideosScreen(nav::popBackStack, onOpen = ::openItem) }
            composable<ChannelRoute> { e -> ListPageScreen(e.toRoute<ChannelRoute>().url, true, nav::popBackStack, ::openItem) }
            composable<PlaylistRoute> { e -> ListPageScreen(e.toRoute<PlaylistRoute>().url, false, nav::popBackStack, ::openItem) }
            composable<HowToRoute> { e ->
                HowToScreen(e.toRoute<HowToRoute>().platform, nav::popBackStack, openInBrowser = ::openUrl,
                    openStatusSaver = { nav.navigate(StatusSaverRoute) })
            }
            composable<DownloadingRoute> { DownloadingScreen(nav::popBackStack) }
            composable<PlayerRoute> { PlayerScreen(onBack = nav::popBackStack, onPip = { FloatingPlayerActivity.start(context) }) }
            composable<BrowserRoute> { e ->
                val r = e.toRoute<BrowserRoute>()
                BrowserScreen(
                    r.url,
                    autoDownload = r.autoDownload,
                    onClose = nav::popBackStack,
                    onOpenVideo = { nav.navigate(VideoRoute(it)) },
                    onOpenHistory = { nav.navigate(BrowserHistoryRoute) },
                    onOpenBookmarks = { nav.navigate(BookmarksRoute) },
                )
            }
            composable<BrowserTabRoute>(
                enterTransition = QtTransitions.tabEnter, exitTransition = QtTransitions.tabExit,
                popEnterTransition = QtTransitions.tabEnter, popExitTransition = QtTransitions.tabExit,
            ) {
                BrowserScreen(
                    startUrl = BROWSER_HOME,
                    isTab = true,
                    onClose = {
                        nav.navigate(HomeRoute) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenVideo = { nav.navigate(VideoRoute(it)) },
                    onOpenHistory = { nav.navigate(BrowserHistoryRoute) },
                    onOpenBookmarks = { nav.navigate(BookmarksRoute) },
                )
            }
            composable<SitesRoute> { SitesScreen(nav::popBackStack, onOpen = ::openUrl, onHistory = { nav.navigate(BrowserHistoryRoute) }) }
            composable<BrowserHistoryRoute> { HistoryScreen(nav::popBackStack, onOpen = ::openUrl) }
            composable<BookmarksRoute> { BookmarksScreen(nav::popBackStack, onOpen = ::openUrl) }
            composable<StatusSaverRoute> { StatusSaverScreen(nav::popBackStack) }
        }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } },
        ) {
            if (miniVisible) MiniPlayer(onExpand = {
                val now = playing.current
                val web = now?.id?.takeIf { it.startsWith("web:") }?.removePrefix("web:")
                when {
                    galleryId != null -> GalleryPlayerActivity.start(context, galleryId)
                    // An online video goes back to its own page, like a gallery video goes back to its player.
                    now != null && web != null -> nav.navigate(
                        VideoRoute(web, now.title.ifBlank { null }, now.artwork),
                    ) { launchSingleTop = true }
                    else -> nav.navigate(PlayerRoute) { launchSingleTop = true }
                }
            }, onPip = { FloatingPlayerActivity.start(context) })
            if (onTab && !barHidden.value) {
                FloatingNavBar(
                    tabs, selectedTab,
                    onSelect = { i ->
                        val atRoot = when (i) {
                            0 -> dest?.hasRoute(HomeRoute::class) == true
                            1 -> dest?.hasRoute(LibraryRoute::class) == true
                            2 -> dest?.hasRoute(BrowserTabRoute::class) == true
                            else -> dest?.hasRoute(SettingsRoute::class) == true
                        }
                        if (!atRoot) nav.navigate(tabs[i].route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    badges = mapOf(1 to activeCount),
                )
            } else if (miniVisible) Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    }
    DownloadSheetHost(onOpenInBrowser = ::openUrl, onOpenExisting = { task ->
        val done = task.status == com.vidgrab.app.core.model.DownloadStatus.COMPLETED
        nav.navigate(if (done) LibraryRoute else DownloadingRoute) { launchSingleTop = true }
    })
}
