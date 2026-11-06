package com.vidgrab.app.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import com.vidgrab.app.core.designsystem.QtColors
import com.vidgrab.app.core.designsystem.signalInk
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.designsystem.shimmer
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val openLibrary: () -> Unit = {},
    val openVault: () -> Unit = {},
)

private val homeTabs = listOf("Search", "YouTube", "Music")

private val quickSources = listOf(
    "YouTube" to "https://m.youtube.com/",
    "Instagram" to "https://www.instagram.com/",
    "Facebook" to "https://m.facebook.com/",
    "TikTok" to "https://www.tiktok.com/",
    "Twitter/X" to "https://x.com/",
)

@Composable
fun HomeScreen(actions: HomeActions, modifier: Modifier = Modifier) {
    val vm = koinViewModel<HomeViewModel>()
    val pager = rememberPagerState { homeTabs.size }
    val scope = rememberCoroutineScope()
    val sheet = koinInject<DownloadSheetController>()
    val context = LocalContext.current
    var clip by remember { mutableStateOf<String?>(null) }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) clip = readClipboardUrl(context) }

    val activeTask by vm.activeTask.collectAsStateWithLifecycle()
    val activeCount by vm.activeCount.collectAsStateWithLifecycle()
    val savedCount by vm.savedCount.collectAsStateWithLifecycle()
    val showTrending by vm.showTrending.collectAsStateWithLifecycle()

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
        SignalPulse(active = activeCount > 0)
        UtilityLine(activeCount = activeCount, savedCount = savedCount)
        Wordmark()
        HomeSearchBar(
            actions = actions,
            onSearch = { query ->
                vm.recordSearch(query)
                actions.openSearchResults(query)
            },
        )
        AnimatedVisibility(clip != null) {
            val url = clip.orEmpty()
            ClipboardLinkCard(
                url = url,
                onDownload = {
                    clip = null
                    if (vm.opensInApp(url)) actions.downloadUrl(url) else sheet.open(Urls.normalize(url))
                },
                onDismiss = { clip = null },
            )
        }
        HomeTabs(pager.currentPage) { index -> scope.launch { pager.animateScrollToPage(index) } }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
            when (page) {
                0 -> SearchTab(
                    vm = vm,
                    actions = actions,
                    onDownload = { item -> sheet.open(item) },
                    showTrending = showTrending,
                    onSeeAll = { scope.launch { pager.animateScrollToPage(1) } },
                )
                1 -> FeedTab(vm, actions, onDownload = { item -> sheet.open(item) })
                2 -> MusicTab(vm, actions, onDownload = { item -> sheet.open(item) })
            }
        }
    }
}

@Composable
private fun SignalPulse(active: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val mint = signalInk(QtColors.LiveMint)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "signal")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 2400,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "phase",
    )
    Canvas(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 18.dp)) {
        val step = 6.dp.toPx()
        val count = (size.width / step).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val amplitude = if (active) 0.25f + 0.75f * abs(sin(phase + i * 0.55f)) else 0.22f
            val h = size.height * amplitude
            val x = i * step + step / 2
            drawLine(
                color = if (i % 6 == 0) mint.copy(alpha = 0.70f) else primary.copy(alpha = 0.28f),
                start = Offset(x, size.height / 2 - h / 2),
                end = Offset(x, size.height / 2 + h / 2),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun UtilityLine(activeCount: Int, savedCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SEARCH / DOWNLOAD / KEEP",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 1.1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (activeCount > 0) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(signalInk(QtColors.LiveMint)))
                Text(
                    "$activeCount LIVE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 0.8.sp,
                    color = signalInk(QtColors.LiveMint),
                    modifier = Modifier.padding(start = 5.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "$savedCount SAVED",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Wordmark() {
    val cs = MaterialTheme.colorScheme
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = cs.onSurface)) { append("Vid") }
            withStyle(SpanStyle(color = signalInk(QtColors.LiveMint))) { append("Grab") }
        },
        fontSize = 27.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-1.1).sp,
        modifier = Modifier.padding(start = 18.dp, top = 8.dp),
    )
}

@Composable
private fun HomeSearchBar(actions: HomeActions, onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)

    fun submit() {
        val text = query.trim()
        focus.clearFocus()
        if (text.isEmpty()) {
            actions.openSearch("")
            return
        }
        if (Urls.looksLikeUrl(text)) actions.downloadUrl(Urls.normalize(text))
        else onSearch(text)
    }

    BasicTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(54.dp).clip(shape)
                    .background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, shape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QtIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (query.isEmpty()) Text(
                        "Search or paste a link",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    inner()
                }
                if (query.isNotEmpty()) QtIconButton(
                    QtIcons.Close, "Clear", { query = "" },
                    tint = cs.onSurfaceVariant, size = 40.dp, iconSize = 17.dp,
                )
                TextButton(
                    onClick = { submit() },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(
                        "GO",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Bold,
                        color = signalInk(QtColors.LiveMint),
                    )
                }
            }
        },
    )
}

@Composable
private fun ClipboardLinkCard(url: String, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mint = signalInk(QtColors.LiveMint)
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp).clip(shape)
            .background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, shape)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(url, size = 34.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                "COPIED LINK",
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                letterSpacing = 0.8.sp,
                color = mint,
            )
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = cs.onSurface,
            )
        }
        QtIconButton(
            QtIcons.Download, "Download", onDownload,
            tint = mint,
            container = mint.copy(alpha = 0.12f),
            size = 38.dp, iconSize = 18.dp,
        )
        QtIconButton(
            QtIcons.Close, "Dismiss", onDismiss,
            tint = cs.onSurfaceVariant, size = 34.dp, iconSize = 15.dp,
        )
    }
}

@Composable
private fun HomeTabs(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        homeTabs.forEachIndexed { index, title ->
            val active = selected == index
            Box(
                Modifier.height(48.dp).clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (active) cs.onSurface else Color.Transparent)
                        .border(1.dp, if (active) Color.Transparent else cs.outlineVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) cs.background else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchTab(
    vm: HomeViewModel,
    actions: HomeActions,
    onDownload: (MediaItem) -> Unit,
    showTrending: Boolean,
    onSeeAll: () -> Unit,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    val activeTask by vm.activeTask.collectAsStateWithLifecycle()
    val activeCount by vm.activeCount.collectAsStateWithLifecycle()

    val trending = if (showTrending) feed.items.take(4) else emptyList()
    val recentItems = recent.take(4)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 14.dp,
            bottom = 22.dp + LocalBottomBarHeight.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BoardHeader(
                title = when {
                    showTrending && trending.isNotEmpty() -> "Trending now"
                    recentItems.isNotEmpty() -> "Recent downloads"
                    else -> "Start here"
                },
                action = when {
                    showTrending && trending.isNotEmpty() -> "SEE ALL"
                    recentItems.isNotEmpty() -> "LIBRARY"
                    else -> "SITES"
                },
                onAction = when {
                    showTrending && trending.isNotEmpty() -> onSeeAll
                    recentItems.isNotEmpty() -> actions.openLibrary
                    else -> actions.openSites
                },
            )
        }

        when {
            showTrending && feed.loading && feed.items.isEmpty() -> items(4) { GridSkeleton() }

            showTrending && feed.error != null && feed.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                InlineError(feed.error.orEmpty()) { vm.loadFeed(false) }
            }

            trending.isNotEmpty() -> gridItems(trending, key = { it.url }) { item ->
                MediaGridTile(item = item, onOpen = { actions.openItem(item) })
            }

            recentItems.isNotEmpty() -> gridItems(recentItems, key = { it.id }) { task ->
                RecentDownloadTile(task = task, onOpen = actions.openLibrary)
            }

            else -> {
                quickSources.take(4).forEach { (name, url) ->
                    item(key = name) {
                        SourceTile(name = name, onClick = { actions.openUrl(url) })
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LiveTransferCard(
                task = activeTask,
                activeCount = activeCount,
                onClick = actions.openLibrary,
            )
        }

        if (recentSearches.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            RecentSearches(
                searches = recentSearches.take(4),
                onOpen = { query ->
                    vm.recordSearch(query)
                    actions.openSearchResults(query)
                },
                onRemove = { query -> vm.forgetSearch(query) },
                onClear = { vm.clearRecentSearches() },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            QuickSources(actions = actions)
        }
    }
}

@Composable
private fun BoardHeader(title: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(40.dp),
        ) {
            Text(
                action,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 0.8.sp,
                color = signalInk(QtColors.LiveMint),
            )
        }
    }
}

@Composable
private fun GridSkeleton() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).shimmer())
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.92f).height(10.dp).clip(RoundedCornerShape(2.dp)).shimmer())
        Box(Modifier.padding(top = 5.dp).fillMaxWidth(0.55f).height(8.dp).clip(RoundedCornerShape(2.dp)).shimmer())
    }
}

@Composable
private fun MediaGridTile(item: MediaItem, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainerHigh)) {
            AsyncImage(
                item.thumbnail, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            OverlayTag(sourceTag(item.url), Modifier.align(Alignment.TopStart).padding(5.dp), QtColors.LiveMint)
            val length = when {
                item.isLive -> "LIVE"
                item.durationSec > 0 -> Format.duration(item.durationSec)
                else -> ""
            }
            if (length.isNotEmpty()) OverlayTag(
                length, Modifier.align(Alignment.BottomEnd).padding(5.dp),
                if (item.isLive) Color(0xFFFF4D5E) else cs.onSurface.copy(alpha = 0.86f),
                background = Color(0xCC0D0F13),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurface,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            listOf(item.uploader, Format.views(item.viewCount)).filter { it.isNotBlank() }.joinToString(" · "),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
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
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainerHigh)) {
            if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(
                QtIcons.MusicNote, null, tint = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            OverlayTag("SAVED", Modifier.align(Alignment.TopStart).padding(5.dp), QtColors.LiveMint)
            if (task.durationSec > 0) OverlayTag(
                Format.duration(task.durationSec), Modifier.align(Alignment.BottomEnd).padding(5.dp),
                cs.onSurface.copy(alpha = 0.86f), background = Color(0xCC0D0F13),
            )
        }
        Text(
            task.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurface,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            listOf(task.quality, Format.bytes(task.totalBytes).takeIf { task.totalBytes > 0 })
                .filter { !it.isNullOrBlank() }.joinToString(" · "),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
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
        Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(4.dp))
            .background(cs.surfaceContainer).border(1.dp, cs.outlineVariant, RoundedCornerShape(4.dp))
            .pressable(scaleDown = 0.97f, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(name, size = 34.dp)
        Column(Modifier.padding(start = 9.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
            Text(
                "OPEN",
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                letterSpacing = 0.7.sp,
                color = signalInk(QtColors.LiveMint),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun OverlayTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    background: Color = Color(0xCC0D0F13),
) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        letterSpacing = 0.5.sp,
        color = color,
        modifier = modifier.clip(RoundedCornerShape(2.dp)).background(background)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun LiveTransferCard(task: DownloadTask?, activeCount: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val progress = task?.progress ?: 0f
    val statusColor = when (task?.status) {
        DownloadStatus.RUNNING, DownloadStatus.MERGING -> signalInk(QtColors.LiveMint)
        DownloadStatus.PAUSED, DownloadStatus.WAITING_NETWORK -> signalInk(QtColors.LiveAmber)
        DownloadStatus.FAILED -> cs.error
        else -> cs.onSurfaceVariant
    }
    val detail = when {
        task == null -> "READY"
        task.totalBytes > 0 -> "${(progress * 100).roundToInt()}% · ${Format.bytes(task.totalBytes)}"
        else -> "${(progress * 100).roundToInt()}%"
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, shape)
            .pressable(scaleDown = 0.995f, onClick = onClick).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (activeCount > 0) "LIVE · $activeCount TRANSFER${if (activeCount == 1) "" else "S"}" else "TRANSFER",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 0.9.sp,
                color = cs.onSurfaceVariant,
            )
            Text(
                if (task == null) "IDLE" else statusLabel(task.status),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 0.9.sp,
                color = statusColor,
            )
        }
        Text(
            task?.title ?: "No active transfers",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            task?.let { transferMeta(it) } ?: "Paste a link or search to start a transfer",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Waveform(
            progress = progress,
            active = task != null,
            modifier = Modifier.fillMaxWidth().height(26.dp).padding(top = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                detail,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = cs.onSurfaceVariant,
            )
            Text(
                task?.let { etaLabel(it) } ?: "—",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Waveform(progress: Float, active: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val mint = signalInk(QtColors.LiveMint)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1300,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    Canvas(modifier) {
        val bars = 28
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        for (i in 0 until bars) {
            val base = if (active) 0.18f + 0.82f * abs(sin(phase + i * 0.6f)) else 0.18f
            val h = (size.height * base).coerceAtLeast(2.dp.toPx())
            val x = i * (barWidth + gap)
            val done = (i + 1).toFloat() / bars <= progress
            drawRoundRect(
                color = if (done) mint else primary.copy(alpha = if (active) 0.38f else 0.18f),
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "QUEUED"
    DownloadStatus.RUNNING -> "FETCHING"
    DownloadStatus.MERGING -> "MERGING"
    DownloadStatus.PAUSED -> "PAUSED"
    DownloadStatus.WAITING_NETWORK -> "WAITING"
    DownloadStatus.COMPLETED -> "SAVED"
    DownloadStatus.FAILED -> "FAILED"
}

private fun transferMeta(task: DownloadTask): String = listOfNotNull(
    task.sourceHost ?: Urls.host(task.pageUrl ?: task.url) ?: "WEB",
    task.quality,
    Format.bytes(task.totalBytes).takeIf { task.totalBytes > 0 },
    Format.duration(task.durationSec).takeIf { task.durationSec > 0 },
).joinToString(" · ")

private fun etaLabel(task: DownloadTask): String {
    if (task.speedBps <= 0 || task.totalBytes <= 0) return "—"
    val remaining = (task.totalBytes - task.downloadedBytes).coerceAtLeast(0)
    if (remaining == 0L) return "DONE"
    return "ETA " + Format.duration(remaining / task.speedBps)
}

private fun sourceTag(url: String): String = when (Urls.platformOf(url)) {
    Platform.YOUTUBE -> "YT"
    Platform.INSTAGRAM -> "IG"
    Platform.FACEBOOK -> "FB"
    Platform.TIKTOK -> "TT"
    Platform.TWITTER -> "X"
    Platform.WHATSAPP -> "WA"
    Platform.SOUNDCLOUD -> "SC"
    Platform.OTHER -> "WEB"
}

@Composable
private fun RecentSearches(
    searches: List<String>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RECENT SEARCHES",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(40.dp)) {
                Text("CLEAR", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = signalInk(QtColors.LiveMint))
            }
        }
        searches.forEachIndexed { index, query ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(query) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QtIcons.History, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                QtIconButton(
                    QtIcons.Close, "Remove", { onRemove(query) },
                    tint = cs.onSurfaceVariant, size = 36.dp, iconSize = 15.dp,
                )
            }
            if (index < searches.lastIndex) HorizontalDivider(color = cs.outlineVariant)
        }
    }
}

@Composable
private fun QuickSources(actions: HomeActions) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            "SOURCES",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            color = cs.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickSources.forEach { (name, url) ->
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(4.dp))
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(4.dp))
                        .pressable(scaleDown = 0.96f, onClick = { actions.openUrl(url) })
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandTile(name, size = 22.dp)
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(4.dp))
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(4.dp))
                    .pressable(scaleDown = 0.96f, onClick = actions.openSites)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QtIcons.Apps, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    "All sites",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurface,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(4.dp)).padding(12.dp),
    ) {
        Text(
            "No signal",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = cs.error,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.height(40.dp)) {
            Text("Retry", color = signalInk(QtColors.LiveMint))
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
                contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp + LocalBottomBarHeight.current),
            ) {
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
        else -> LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalBottomBarHeight.current)) {
            items(ui.sections, key = { it.title }) { section ->
                com.vidgrab.app.core.designsystem.SectionHeader(section.title)
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
