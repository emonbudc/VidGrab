package com.vidgrab.app.feature.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vidgrab.app.core.common.Format
import com.vidgrab.app.core.common.Platform
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.designsystem.IconTile
import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import com.vidgrab.app.core.designsystem.LocalQtExtras
import com.vidgrab.app.core.designsystem.QtColors
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.SectionHeader
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.designsystem.shimmer
import com.vidgrab.app.core.designsystem.signalInk
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.model.ContentType
import com.vidgrab.app.core.model.DownloadStatus
import com.vidgrab.app.core.model.DownloadTask
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.ui.BrandTile
import com.vidgrab.app.core.ui.ErrorBox
import com.vidgrab.app.core.ui.FeedSkeleton
import com.vidgrab.app.core.ui.FooterLoading
import com.vidgrab.app.core.ui.OnNearEnd
import com.vidgrab.app.core.ui.TilesSkeleton
import com.vidgrab.app.core.ui.VideoFeedCard
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.io.File
import kotlin.math.roundToInt

/** Callbacks the app supplies so this feature never depends on other features. */
class HomeActions(
    val openSearch: (String) -> Unit,
    val openSearchResults: (String) -> Unit,
    val openUrl: (String) -> Unit,
    /** A link the user pasted/copied: go straight to downloading it. */
    val downloadUrl: (String) -> Unit,
    val openItem: (MediaItem) -> Unit,
    val openChannel: (String) -> Unit,
    val openHowTo: (String) -> Unit,
    val openSites: () -> Unit,
    val openSettings: () -> Unit = {},
    val openLibrary: () -> Unit = {},
    val openVault: () -> Unit = {},
)

private val homeTabs = listOf("Discover", "YouTube", "Music")

private val quickSources = listOf(
    "YouTube" to "https://m.youtube.com/",
    "TikTok" to "https://www.tiktok.com/",
    "Facebook" to "https://m.facebook.com/",
    "Instagram" to "https://www.instagram.com/",
)

@Composable
fun HomeScreen(actions: HomeActions, modifier: Modifier = Modifier) {
    val vm = koinViewModel<HomeViewModel>()
    val pager = rememberPagerState { homeTabs.size }
    val scope = rememberCoroutineScope()
    val sheet = koinInject<DownloadSheetController>()
    val context = LocalContext.current
    val clipboardOn by vm.clipboardDetection.collectAsStateWithLifecycle()
    var clip by remember { mutableStateOf<String?>(null) }
    var dismissedClip by rememberSaveable { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val showTrending by vm.showTrending.collectAsStateWithLifecycle()
    LaunchedEffect(focused, clipboardOn) {
        if (!focused || !clipboardOn) {
            clip = null
            return@LaunchedEffect
        }
        val found = readClipboardUrl(context)
        clip = found?.takeIf { it != dismissedClip }
    }

    val activeCount by vm.activeCount.collectAsStateWithLifecycle()

    LaunchedEffect(pager.currentPage) {
        when (pager.currentPage) {
            0 -> if (showTrending) vm.ensureFeed()
            1 -> vm.ensureFeed()
            2 -> vm.ensureMusic()
        }
    }
    LaunchedEffect(showTrending) { if (showTrending) vm.ensureFeed() }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
    ) {
        HomeHeader(
            activeCount = activeCount,
            onSettings = actions.openSettings,
        )
        HomeTabs(pager.currentPage) { index -> scope.launch { pager.animateScrollToPage(index) } }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
            when (page) {
                0 -> DiscoverTab(
                    vm = vm,
                    actions = actions,
                    sheet = sheet,
                    clipboardUrl = clip,
                    clipboardOn = clipboardOn,
                    onDismissClip = {
                        dismissedClip = clip
                        clip = null
                    },
                    onOpenMusic = { scope.launch { pager.animateScrollToPage(2) } },
                    onSeeAll = { scope.launch { pager.animateScrollToPage(1) } },
                    onPause = vm::pause,
                    onResume = vm::resume,
                )
                1 -> FeedTab(vm, actions, onDownload = { item -> sheet.open(item) })
                2 -> MusicTab(vm, actions, onDownload = { item -> sheet.open(item) })
            }
        }
    }
}

@Composable
private fun HomeHeader(activeCount: Int, onSettings: () -> Unit) {
    var proSheet by remember { mutableStateOf(false) }
    val ex = LocalQtExtras.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(ex.brand),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.Play, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("Vid") }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Grab") }
            },
            fontSize = 23.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.7).sp,
            modifier = Modifier.padding(start = 10.dp),
        )
        if (activeCount > 0) {
            Row(
                Modifier.padding(start = 8.dp).clip(RoundedCornerShape(9.dp))
                    .background(signalInk(QtColors.LiveMint).copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(signalInk(QtColors.LiveMint)))
                Text(
                    "$activeCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = signalInk(QtColors.LiveMint),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .pressable(scaleDown = 0.95f, onClick = { proSheet = true }).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QtIcons.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            Text("Pro", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 5.dp))
        }
        QtIconButton(QtIcons.Settings, "Settings", onSettings, tint = MaterialTheme.colorScheme.onSurface)
    }
    if (proSheet) ProSheet(onDismiss = { proSheet = false })
}

@Composable
private fun ProSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(LocalQtExtras.current.brand),
                    contentAlignment = Alignment.Center,
                ) { Icon(QtIcons.Star, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("VidGrab Pro", style = MaterialTheme.typography.headlineSmall)
                    Text("Everything is already unlocked", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "No ads, no sign-in and no download limits. VidGrab is open source — if it saves you time, share it or leave a star.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.vidgrab.app.core.designsystem.PillButton(
                    "Share app", {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Download videos and music with VidGrab: https://github.com/emonbudc/VidGrab",
                                ),
                                "Share VidGrab",
                            ),
                        )
                    },
                    Modifier.weight(1f), icon = QtIcons.Share,
                )
                com.vidgrab.app.core.designsystem.PillButton(
                    "GitHub", {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/emonbudc/VidGrab")))
                        }
                    },
                    Modifier.weight(1f), icon = QtIcons.OpenInNew, secondary = true,
                )
            }
        }
    }
}

@Composable
private fun HomeTabs(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        homeTabs.forEachIndexed { index, title ->
            val active = selected == index
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (active) cs.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) cs.onSurface else cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiscoverTab(
    vm: HomeViewModel,
    actions: HomeActions,
    sheet: DownloadSheetController,
    clipboardUrl: String?,
    clipboardOn: Boolean,
    onDismissClip: () -> Unit,
    onOpenMusic: () -> Unit,
    onSeeAll: () -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    val activeTask by vm.activeTask.collectAsStateWithLifecycle()
    val activeCount by vm.activeCount.collectAsStateWithLifecycle()
    val showTrending by vm.showTrending.collectAsStateWithLifecycle()

    val trending = if (showTrending) feed.items.take(6) else emptyList()
    val recentItems = recent.take(4)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 2.dp,
            bottom = 24.dp + LocalBottomBarHeight.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Hero() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SmartDownloadInput(
                clipboardUrl = clipboardUrl,
                clipboardOn = clipboardOn,
                onDismissClip = onDismissClip,
                onOpenSearch = { actions.openSearch("") },
                onSearch = { query ->
                    vm.recordSearch(query)
                    actions.openSearchResults(query)
                },
                onDownload = { url ->
                    if (vm.opensInApp(url)) actions.downloadUrl(url) else sheet.open(Urls.normalize(url))
                },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PlatformShortcuts(actions = actions, onMusic = onOpenMusic)
        }

        if (activeTask != null) item(span = { GridItemSpan(maxLineSpan) }) {
            ActiveDownloadCard(
                task = activeTask!!,
                activeCount = activeCount,
                onClick = actions.openLibrary,
                onPause = { onPause(activeTask!!.id) },
                onResume = { onResume(activeTask!!.id) },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            BoardHeader(
                title = when {
                    showTrending && trending.isNotEmpty() -> "Trending now"
                    recentItems.isNotEmpty() -> "Recent downloads"
                    else -> "Start here"
                },
                action = when {
                    showTrending && trending.isNotEmpty() -> "See all"
                    recentItems.isNotEmpty() -> "Library"
                    else -> "All sites"
                },
                onAction = when {
                    showTrending && trending.isNotEmpty() -> onSeeAll
                    recentItems.isNotEmpty() -> actions.openLibrary
                    else -> actions.openSites
                },
            )
        }

        when {
            showTrending && feed.loading && feed.items.isEmpty() -> gridItems(4) { GridSkeleton() }

            showTrending && feed.error != null && feed.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                InlineError(feed.error.orEmpty()) { vm.loadFeed(false) }
            }

            trending.isNotEmpty() -> gridItems(trending, key = { it.url }) { item ->
                MediaGridTile(item = item, onOpen = { actions.openItem(item) })
            }

            recentItems.isNotEmpty() -> gridItems(recentItems, key = { it.id }) { task ->
                RecentDownloadTile(task = task, onOpen = actions.openLibrary)
            }

            else -> quickSources.take(4).forEach { (name, url) ->
                item(key = name) {
                    SourceTile(name = name, onClick = { actions.openUrl(url) })
                }
            }
        }

        if (recentSearches.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            RecentSearches(
                searches = recentSearches.take(6),
                onOpen = { query ->
                    vm.recordSearch(query)
                    actions.openSearchResults(query)
                },
                onClear = { vm.clearRecentSearches() },
            )
        }
    }
}

@Composable
private fun Hero() {
    val ex = LocalQtExtras.current
    Box(
        Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF101B35), Color(0xFF1D3268), ex.brandStart))),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = (-26).dp, end = (-16).dp).size(124.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = 0.06f)),
        )
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = (-30).dp).size(92.dp)
                .clip(CircleShape).background(ex.brandEnd.copy(alpha = 0.16f)),
        )
        Column(Modifier.fillMaxHeight().padding(start = 20.dp, top = 18.dp, bottom = 18.dp, end = 96.dp)) {
            Text(
                "DOWNLOAD • WATCH • ENJOY",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.66f),
                letterSpacing = 1.2.sp,
            )
            Text(
                "Your Videos.\nYour Way.",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                lineHeight = 31.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Download, save and enjoy videos in one place.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.74f),
                maxLines = 2,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(58.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QtIcons.Play, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun SmartDownloadInput(
    clipboardUrl: String?,
    clipboardOn: Boolean,
    onDismissClip: () -> Unit,
    onOpenSearch: () -> Unit,
    onSearch: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val isUrl = Urls.looksLikeUrl(query)
    val detected = if (isUrl) platformName(query) else null

    fun submit() {
        val text = query.trim()
        focus.clearFocus()
        if (text.isEmpty()) {
            onOpenSearch()
            return
        }
        if (Urls.looksLikeUrl(text)) {
            onDownload(Urls.normalize(text))
            query = ""
        } else onSearch(text)
    }

    fun paste() {
        val text = readClipboardUrl(context) ?: return
        query = text
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(24.dp)).padding(12.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(QtIcons.Link, tint = cs.primary, size = 38.dp)
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        if (query.isEmpty()) Text(
                            "Paste a link or search...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                        inner()
                    }
                    if (query.isNotEmpty()) {
                        QtIconButton(QtIcons.Close, "Clear", { query = "" }, tint = cs.onSurfaceVariant, size = 38.dp, iconSize = 17.dp)
                    }
                    QtIconButton(
                        QtIcons.ContentPaste, "Paste link", ::paste,
                        tint = cs.primary, container = cs.primaryContainer, size = 40.dp, iconSize = 19.dp,
                    )
                }
            },
        )
        AnimatedVisibility(query.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isUrl) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(signalInk(QtColors.LiveMint)))
                    Text(
                        "$detected link detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = signalInk(QtColors.LiveMint),
                        modifier = Modifier.padding(start = 7.dp),
                    )
                } else {
                    Text(
                        "Search for this instead",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            focus.clearFocus()
                            onSearch(query.trim())
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Search", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    }
                }
            }
        }
        com.vidgrab.app.core.designsystem.PillButton(
            "Download",
            { submit() },
            Modifier.fillMaxWidth().padding(top = 6.dp),
            icon = QtIcons.Download,
            enabled = isUrl,
        )
        AnimatedVisibility(clipboardOn && clipboardUrl != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainerHigh)
                    .pressable(scaleDown = 0.99f, onClick = {
                        query = clipboardUrl.orEmpty()
                        onDismissClip()
                    })
                    .padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandTile(clipboardUrl.orEmpty(), size = 28.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Link copied", style = MaterialTheme.typography.labelSmall, color = signalInk(QtColors.LiveMint))
                    Text(
                        Urls.host(clipboardUrl.orEmpty()) ?: clipboardUrl.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QtIconButton(
                    QtIcons.Download, "Download copied link",
                    {
                        onDownload(clipboardUrl.orEmpty())
                        onDismissClip()
                    },
                    tint = cs.primary, container = cs.primaryContainer, size = 38.dp, iconSize = 18.dp,
                )
                QtIconButton(QtIcons.Close, "Dismiss", onDismissClip, tint = cs.onSurfaceVariant, size = 32.dp, iconSize = 15.dp)
            }
        }
    }
}

@Composable
private fun PlatformShortcuts(actions: HomeActions, onMusic: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Quick download",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quickSources.forEach { (name, url) ->
                Shortcut(name = name, onClick = { actions.openUrl(url) }, modifier = Modifier.weight(1f))
            }
            Shortcut(name = "Music", onClick = onMusic, modifier = Modifier.weight(1f), music = true)
        }
    }
}

@Composable
private fun Shortcut(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, music: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).pressable(scaleDown = 0.95f, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (music) Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.MusicNote, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(23.dp)) }
        else BrandTile(name, size = 46.dp)
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun BoardHeader(title: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GridSkeleton() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).shimmer())
        Box(Modifier.padding(top = 9.dp).fillMaxWidth(0.92f).height(12.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.55f).height(9.dp).clip(RoundedCornerShape(5.dp)).shimmer())
    }
}

@Composable
private fun MediaGridTile(item: MediaItem, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)) {
            AsyncImage(
                item.thumbnail, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            OverlayTag(sourceTag(item.url), Modifier.align(Alignment.TopStart).padding(7.dp), QtColors.LiveMint)
            val length = when {
                item.isLive -> "LIVE"
                item.durationSec > 0 -> Format.duration(item.durationSec)
                else -> ""
            }
            if (length.isNotEmpty()) OverlayTag(
                length, Modifier.align(Alignment.BottomEnd).padding(7.dp),
                if (item.isLive) Color(0xFFFF4D5E) else Color.White,
                background = Color(0xCC0D1526),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurface,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOf(item.uploader, Format.views(item.viewCount)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun RecentDownloadTile(task: DownloadTask, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val model: Any? = if (task.type == ContentType.AUDIO) null else File(task.filePath).takeIf { it.exists() } ?: task.thumbnail
    Column(Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)) {
            if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(
                QtIcons.MusicNote, null, tint = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            OverlayTag("SAVED", Modifier.align(Alignment.TopStart).padding(7.dp), QtColors.LiveMint)
            if (task.durationSec > 0) OverlayTag(
                Format.duration(task.durationSec), Modifier.align(Alignment.BottomEnd).padding(7.dp),
                Color.White, background = Color(0xCC0D1526),
            )
        }
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurface,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOf(task.quality, Format.bytes(task.totalBytes).takeIf { task.totalBytes > 0 })
                .filter { !it.isNullOrBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun SourceTile(name: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(18.dp))
            .background(cs.surface).border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))
            .pressable(scaleDown = 0.97f, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(name, size = 38.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
            Text(
                "Open",
                style = MaterialTheme.typography.labelSmall,
                color = signalInk(QtColors.LiveMint),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun OverlayTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    background: Color = Color(0xCC0D1526),
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = color,
        modifier = modifier.clip(RoundedCornerShape(7.dp)).background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ActiveDownloadCard(
    task: DownloadTask,
    activeCount: Int,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val progress by animateFloatAsState(task.progress.coerceIn(0f, 1f), label = "download")
    val running = task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED ||
        task.status == DownloadStatus.WAITING_NETWORK
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp))
            .pressable(scaleDown = 0.995f, onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)) {
                val model: Any? = if (task.type == ContentType.AUDIO) null else File(task.filePath).takeIf { it.exists() } ?: task.thumbnail
                if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(QtIcons.Downloading, null, tint = cs.primary, modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append((progress * 100).roundToInt()).append("%")
                        if (task.speedBps > 0) append(" · ").append(Format.speed(task.speedBps))
                        etaLabel(task)?.let { append(" · ").append(it) }
                        append(" · ").append(statusLabel(task.status))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (task.status != DownloadStatus.MERGING) QtIconButton(
                if (running) QtIcons.Pause else QtIcons.Play,
                if (running) "Pause" else "Resume",
                if (running) onPause else onResume,
                tint = cs.primary,
                container = cs.primaryContainer,
                size = 42.dp,
                iconSize = 20.dp,
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 11.dp).height(6.dp).clip(CircleShape).background(cs.surfaceContainerHigh)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(CircleShape).background(LocalQtExtras.current.brand))
        }
        if (activeCount > 1) Text(
            "${activeCount - 1} more downloading",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.RUNNING -> "Downloading"
    DownloadStatus.MERGING -> "Processing"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.WAITING_NETWORK -> "Waiting for network"
    DownloadStatus.COMPLETED -> "Saved"
    DownloadStatus.FAILED -> "Failed"
}

private fun etaLabel(task: DownloadTask): String? {
    if (task.speedBps <= 0 || task.totalBytes <= 0) return null
    val remaining = (task.totalBytes - task.downloadedBytes).coerceAtLeast(0)
    if (remaining == 0L) return null
    return "ETA " + Format.duration(remaining / task.speedBps)
}

private fun sourceTag(url: String): String = when (Urls.platformOf(url)) {
    Platform.YOUTUBE -> "YouTube"
    Platform.INSTAGRAM -> "Instagram"
    Platform.FACEBOOK -> "Facebook"
    Platform.TIKTOK -> "TikTok"
    Platform.TWITTER -> "X"
    Platform.WHATSAPP -> "WhatsApp"
    Platform.SOUNDCLOUD -> "SoundCloud"
    Platform.OTHER -> "Web"
}

private fun platformName(url: String): String = when (Urls.platformOf(url)) {
    Platform.YOUTUBE -> "YouTube"
    Platform.INSTAGRAM -> "Instagram"
    Platform.FACEBOOK -> "Facebook"
    Platform.TIKTOK -> "TikTok"
    Platform.TWITTER -> "X"
    Platform.WHATSAPP -> "WhatsApp"
    Platform.SOUNDCLOUD -> "SoundCloud"
    Platform.OTHER -> "Video"
}

@Composable
private fun RecentSearches(
    searches: List<String>,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recent searches",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("Clear", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            searches.forEach { query ->
                Row(
                    Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(cs.surface)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(19.dp))
                        .pressable(scaleDown = 0.96f, onClick = { onOpen(query) }).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QtIcons.History, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    Text(
                        query,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Text("Couldn't load this", style = MaterialTheme.typography.titleSmall, color = cs.error)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Try again", color = cs.primary)
        }
    }
}

private fun readClipboardUrl(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    Urls.extractUrl(text)
}.getOrNull()

@Composable
private fun FeedTab(vm: HomeViewModel, actions: HomeActions, onDownload: (MediaItem) -> Unit) {
    val ui by vm.feed.collectAsStateWithLifecycle()
    when {
        ui.loading && ui.items.isEmpty() -> FeedSkeleton()
        ui.error != null && ui.items.isEmpty() -> ErrorBox(ui.error!!, { vm.loadFeed(false) })
        else -> PullToRefreshBox(ui.refreshing, onRefresh = { vm.loadFeed(true) }, Modifier.fillMaxSize()) {
            val state = rememberLazyListState()
            state.OnNearEnd { vm.loadMoreFeed() }
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp + LocalBottomBarHeight.current),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Fresh picks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "A mix from different searches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        QtIconButton(
                            QtIcons.Shuffle, "Shuffle videos", { vm.loadFeed(refresh = true) },
                            container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                }
                items(ui.items, key = { it.url }) { item ->
                    Box(Modifier.animateItem()) {
                        VideoFeedCard(
                            item,
                            onClick = { actions.openItem(item) },
                            onDownload = { onDownload(item) },
                            onChannel = item.uploaderUrl?.let { u -> { actions.openChannel(u) } },
                        )
                    }
                }
                if (ui.loadingMore) item { FooterLoading() }
            }
        }
    }
}

@Composable
private fun MusicTab(vm: HomeViewModel, actions: HomeActions, onDownload: (MediaItem) -> Unit) {
    val ui by vm.music.collectAsStateWithLifecycle()
    when {
        ui.loading -> TilesSkeleton()
        ui.error != null && ui.sections.isEmpty() -> ErrorBox(ui.error!!, vm::loadMusic)
        else -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp + LocalBottomBarHeight.current)) {
            items(ui.sections, key = { it.title }) { section ->
                SectionHeader(section.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.items, key = { it.url }) { item ->
                        com.vidgrab.app.core.ui.MediaTile(
                            item,
                            onClick = { actions.openItem(item) },
                            onDownload = {
                                if (item.kind == ItemKind.VIDEO) onDownload(item) else actions.openItem(item)
                            },
                        )
                    }
                }
            }
        }
    }
}
