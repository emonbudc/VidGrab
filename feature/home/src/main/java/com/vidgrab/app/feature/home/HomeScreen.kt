package com.vidgrab.app.feature.home

import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import com.vidgrab.app.core.designsystem.icon.QtIcons
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.ui.BrandTile
import com.vidgrab.app.core.ui.FeedSkeleton
import com.vidgrab.app.core.ui.TilesSkeleton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import com.vidgrab.app.core.designsystem.shimmer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.designsystem.BrandTitle
import com.vidgrab.app.core.designsystem.LocalQtExtras
import com.vidgrab.app.core.designsystem.SectionHeader
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.ui.ErrorBox
import com.vidgrab.app.core.ui.FooterLoading
import com.vidgrab.app.core.ui.LoadingBox
import com.vidgrab.app.core.ui.MediaTile
import com.vidgrab.app.core.ui.OnNearEnd
import com.vidgrab.app.core.ui.VideoFeedCard
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Callbacks the app supplies so this feature never depends on other features. */
class HomeActions(
    val openSearch: (String) -> Unit,
    val openUrl: (String) -> Unit,
    /** A link the user pasted/copied: go straight to downloading it. */
    val downloadUrl: (String) -> Unit,
    val openItem: (MediaItem) -> Unit,
    val openChannel: (String) -> Unit,
    val openHowTo: (String) -> Unit,
    val openSites: () -> Unit,
    val openLibrary: () -> Unit = {},
    val openVault: () -> Unit = {},
)

private val tabs = listOf("Search", "YouTube", "Music", "More")

@Composable
fun HomeScreen(actions: HomeActions, modifier: Modifier = Modifier) {
    val vm = koinViewModel<HomeViewModel>()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val sheet = koinInject<DownloadSheetController>()
    val context = LocalContext.current
    var clip by remember { mutableStateOf<String?>(null) }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) clip = readClipboardUrl(context) }

    LaunchedEffect(pager.currentPage) {
        when (pager.currentPage) {
            // The Search page warms the YouTube tab only when it shows the trending row (Settings → Trending on home).
            0 -> { if (vm.showTrending.value) vm.ensureFeed() }
            1 -> vm.ensureFeed()
            2 -> vm.ensureMusic()
        }
    }

    Column(modifier.fillMaxSize()) {
        HomeTopBar(actions, vm, sheet, clip)
        HomeTabs(pager.currentPage + pager.currentPageOffsetFraction) { i -> scope.launch { pager.animateScrollToPage(i) } }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            when (page) {
                0 -> DiscoverTab(vm, actions, onDownload = sheet::open, onSeeTrending = { scope.launch { pager.animateScrollToPage(1) } }, onSeeMusic = { scope.launch { pager.animateScrollToPage(2) } })
                1 -> FeedTab(vm, actions, onDownload = sheet::open)
                2 -> MusicTab(vm, actions, onDownload = sheet::open)
                else -> MoreTab(actions)
            }
        }
    }
}

@Composable
private fun HomeTopBar(actions: HomeActions, vm: HomeViewModel, sheet: DownloadSheetController, clip: String?) {
    val ex = LocalQtExtras.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(ex.brand),
                contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
            Text(
                "VidGrab", style = BrandTitle.copy(fontSize = 24.sp), color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Downloader", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pressable(scaleDown = 0.985f, onClick = { actions.openSearch("") })
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QtIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
            Text("Search videos, music or paste a URL", Modifier.weight(1f).padding(horizontal = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(QtIcons.Search, "Search", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
        AnimatedVisibility(clip != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val url = clip.orEmpty()
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pressable(scaleDown = 0.98f, onClick = { actions.downloadUrl(url) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandTile(url, size = 36.dp, label = Urls.host(url) ?: "L")
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Download copied link", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                }
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary)
                        .pressable(scaleDown = 0.9f, onClick = {
                            if (vm.opensInApp(url)) actions.downloadUrl(url) else sheet.open(Urls.normalize(url))
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(QtIcons.Download, "Download", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Compact category chips that stay in sync with the pager. */
@Composable
private fun HomeTabs(position: Float, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, title ->
            val active = kotlin.math.abs(position - i) < 0.5f
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                    .pressable(scaleDown = 0.95f, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiscoverTab(
    vm: HomeViewModel,
    actions: HomeActions,
    onDownload: (MediaItem) -> Unit,
    onSeeTrending: () -> Unit,
    onSeeMusic: () -> Unit,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val music by vm.music.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val showTrending by vm.showTrending.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp + LocalBottomBarHeight.current)) {
            item(key = "sites") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { QuickSites(actions) }
            }
            if (showTrending && (feed.items.isNotEmpty() || feed.loading)) item(key = "trending") {
                DiscoverHeader("Trending now", QtIcons.TrendingUp, onSeeTrending)
                TrendingCarousel(feed.items.take(12), loading = feed.items.isEmpty(), onOpen = actions.openItem, onDownload = onDownload)
            }
            if (recent.isNotEmpty()) item(key = "recent") {
                DiscoverHeader("Your recent downloads", QtIcons.DownloadDone, actions.openLibrary)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.id }) { t ->
                        Column(Modifier.width(150.dp).pressable(scaleDown = 0.96f, onClick = actions.openLibrary)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center) {
                                Icon(if (t.type == com.vidgrab.app.core.model.ContentType.AUDIO) QtIcons.MusicNote else QtIcons.PlayCircle, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                val model: Any? = t.thumbnail ?: java.io.File(t.filePath).takeIf { it.exists() && t.type == com.vidgrab.app.core.model.ContentType.VIDEO }
                                if (model != null) coil3.compose.AsyncImage(model, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
}

@Composable
private fun DiscoverHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onMore: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (onMore != null) Row(
            Modifier.clip(RoundedCornerShape(6.dp)).pressable(scaleDown = 0.94f, onClick = onMore).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("See all", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

/** Video cards in a horizontal rail, thumbnail first, title and channel underneath. */
@Composable
private fun TrendingCarousel(items: List<MediaItem>, loading: Boolean, onOpen: (MediaItem) -> Unit, onDownload: (MediaItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) items(3) {
            Column(Modifier.width(220.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).shimmer())
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.9f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            }
        }
        itemsIndexed(items, key = { _, it -> it.url }) { _, item ->
            Column(
                Modifier.width(220.dp).pressable(scaleDown = 0.98f, role = null, onClick = { onOpen(item) }),
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    coil3.compose.AsyncImage(
                        item.thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isLive || item.durationSec > 0) Text(
                        if (item.isLive) "LIVE" else com.vidgrab.app.core.common.Format.duration(item.durationSec),
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (item.isLive) Color(0xFFE5243B) else Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.uploader, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    if (!item.isLive) Box(
                        Modifier.padding(start = 4.dp).size(30.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .pressable(scaleDown = 0.85f, onClick = { onDownload(item) }),
                        contentAlignment = Alignment.Center,
                    ) { Icon(QtIcons.Download, "Download", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp)) }
                }
            }
        }
    }
}

@Composable
private fun QuickSites(actions: HomeActions) {
    val sites = listOf("YouTube" to "https://m.youtube.com/", "Instagram" to "https://www.instagram.com/",
        "Facebook" to "https://m.facebook.com/", "TikTok" to "https://www.tiktok.com/")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        sites.forEach { (name, url) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).pressable(scaleDown = 0.9f, onClick = { actions.openUrl(url) }).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandTile(name, size = 46.dp)
                Text(name, style = MaterialTheme.typography.labelSmall, fontSize = 10.5.sp, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).pressable(scaleDown = 0.9f, onClick = actions.openSites).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                Icon(QtIcons.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Text("Sites", style = MaterialTheme.typography.labelSmall, fontSize = 10.5.sp, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
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
            LazyColumn(state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp + LocalBottomBarHeight.current)) {
                items(ui.items, key = { it.url }) { item ->
                    Box(Modifier.animateItem()) { VideoFeedCard(
                        item,
                        onClick = { actions.openItem(item) },
                        onDownload = { onDownload(item) },
                        onChannel = item.uploaderUrl?.let { u -> { actions.openChannel(u) } },
                    ) }
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
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarHeight.current)) {
            items(ui.sections, key = { it.title }) { section ->
                SectionHeader(section.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.items, key = { it.url }) { item ->
                        MediaTile(item, onClick = { actions.openItem(item) }, onDownload = {
                            if (item.kind == ItemKind.VIDEO) onDownload(item) else actions.openItem(item)
                        })
                    }
                }
            }
        }
    }
}
