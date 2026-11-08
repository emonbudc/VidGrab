package com.vidgrab.app.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.ui.BrandTile
import com.vidgrab.app.core.ui.EmptyBox
import com.vidgrab.app.core.ui.ErrorBox
import com.vidgrab.app.core.ui.FeedSkeleton
import com.vidgrab.app.core.ui.FooterLoading
import com.vidgrab.app.core.ui.OnNearEnd
import com.vidgrab.app.core.ui.VideoFeedCard
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

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

private data class SocialSource(val name: String, val url: String)

private val socialSources = listOf(
    SocialSource("YouTube", "https://m.youtube.com/"),
    SocialSource("TikTok", "https://www.tiktok.com/"),
    SocialSource("Facebook", "https://m.facebook.com/"),
    SocialSource("Instagram", "https://www.instagram.com/"),
)

@Composable
fun HomeScreen(actions: HomeActions, modifier: Modifier = Modifier) {
    val vm = koinViewModel<HomeViewModel>()
    val sheet = koinInject<DownloadSheetController>()
    val context = LocalContext.current
    val category by vm.category.collectAsStateWithLifecycle()
    val clipboardOn by vm.clipboardDetection.collectAsStateWithLifecycle()
    var clip by remember { mutableStateOf<String?>(null) }
    var dismissedClip by rememberSaveable { mutableStateOf<String?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(focused, clipboardOn) {
        if (!focused || !clipboardOn) {
            clip = null
            return@LaunchedEffect
        }
        clip = readClipboardUrl(context)?.takeIf { it != dismissedClip }
    }
    LaunchedEffect(Unit) { vm.ensureFeed() }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
    ) {
        HomeHeader(
            onLogoClick = { actions.openSearch("") },
            onOpen = actions.openUrl,
            onMusic = { vm.selectCategory(HomeCategory.MUSIC) },
        )
        CategoryRow(selected = category, onSelect = vm::selectCategory)
        if (clipboardOn && clip != null) ClipboardSuggestion(
            url = clip.orEmpty(),
            onDownload = { url ->
                if (vm.opensInApp(url)) actions.downloadUrl(url) else sheet.open(Urls.normalize(url))
            },
            onDismiss = {
                dismissedClip = clip
                clip = null
            },
        )
        HomeFeed(
            vm = vm,
            actions = actions,
            sheet = sheet,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeHeader(
    onLogoClick: () -> Unit,
    onOpen: (String) -> Unit,
    onMusic: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.vidgrab_wordmark),
            contentDescription = "VidGrab. Search or paste a link",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(30.dp).pressable(scaleDown = 0.96f, role = Role.Button, onClick = onLogoClick),
        )
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
            contentPadding = PaddingValues(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(socialSources, key = { it.name }) { source ->
                SocialShortcut(name = source.name, onClick = { onOpen(source.url) })
            }
            item(key = "Music") { SocialShortcut(name = "Music", onClick = onMusic, music = true) }
        }
    }
}

@Composable
private fun SocialShortcut(name: String, onClick: () -> Unit, music: Boolean = false) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
            .semantics { contentDescription = name }
            .pressable(scaleDown = 0.9f, role = Role.Button, onClick = onClick),
    ) {
        if (music) Box(
            Modifier.fillMaxSize().background(Color(0xFFE2F8F0)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QtIcons.MusicNote, null, tint = Color(0xFF12A987), modifier = Modifier.size(22.dp))
        } else BrandTile(name, Modifier.fillMaxSize(), size = 40.dp)
    }
}

@Composable
private fun CategoryRow(selected: HomeCategory, onSelect: (HomeCategory) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HomeCategory.entries, key = { it.name }) { category ->
            val active = category == selected
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) cs.onSurface else cs.surfaceContainerHigh)
                    .pressable(scaleDown = 0.96f, role = Role.Tab, onClick = { onSelect(category) })
                    .padding(horizontal = 17.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    category.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) cs.surface else cs.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ClipboardSuggestion(
    url: String,
    onDownload: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
            .padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(url, size = 30.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                "Link copied",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
            )
            Text(
                Urls.host(url) ?: url,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QtIconButton(
            QtIcons.Download, "Download copied link", { onDownload(url) },
            tint = cs.primary, container = cs.primaryContainer, size = 38.dp, iconSize = 18.dp,
        )
        QtIconButton(QtIcons.Close, "Dismiss", onDismiss, tint = cs.onSurfaceVariant, size = 32.dp, iconSize = 15.dp)
    }
}

@Composable
private fun HomeFeed(
    vm: HomeViewModel,
    actions: HomeActions,
    sheet: DownloadSheetController,
    modifier: Modifier = Modifier,
) {
    val ui by vm.feed.collectAsStateWithLifecycle()
    when {
        ui.loading && ui.items.isEmpty() -> Box(modifier.fillMaxSize()) { FeedSkeleton() }
        ui.error != null && ui.items.isEmpty() -> ErrorBox(ui.error!!, { vm.loadFeed(false) }, modifier)
        ui.items.isEmpty() -> EmptyBox(
            "Nothing here yet",
            "Try another category or pull down to refresh.",
            modifier,
        )
        else -> PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = { vm.loadFeed(true) },
            modifier = modifier.fillMaxSize(),
        ) {
            val state = rememberLazyListState()
            LaunchedEffect(ui.refreshing) { if (!ui.refreshing) state.scrollToItem(0) }
            state.OnNearEnd { vm.loadMoreFeed() }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 24.dp + LocalBottomBarHeight.current,
                ),
            ) {
                items(ui.items, key = { it.url }) { item ->
                    Box(Modifier.animateItem()) {
                        VideoFeedCard(
                            item,
                            onClick = { actions.openItem(item) },
                            onDownload = { sheet.open(item) },
                            onChannel = item.uploaderUrl?.let { url -> { actions.openChannel(url) } },
                        )
                    }
                }
                if (ui.loadingMore) item { FooterLoading() }
            }
        }
    }
}

private fun readClipboardUrl(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    Urls.extractUrl(text)
}.getOrNull()
