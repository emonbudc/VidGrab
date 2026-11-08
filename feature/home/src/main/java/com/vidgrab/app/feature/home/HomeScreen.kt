package com.vidgrab.app.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.ui.EmptyBox
import com.vidgrab.app.core.designsystem.R as DsR
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
    val openItem: (MediaItem) -> Unit,
    val openChannel: (String) -> Unit,
    val openHowTo: (String) -> Unit,
    val openSettings: () -> Unit = {},
    val openLibrary: () -> Unit = {},
    val openVault: () -> Unit = {},
)

@Composable
fun HomeScreen(actions: HomeActions, modifier: Modifier = Modifier) {
    val vm = koinViewModel<HomeViewModel>()
    val sheet = koinInject<DownloadSheetController>()
    val category by vm.category.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.ensureFeed() }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
    ) {
        HomeHeader(
            onLogoClick = { actions.openSearch("") },
            onSearch = { actions.openSearch("") },
        )
        HomeSearchBar(onClick = { actions.openSearch("") })
        CategoryRow(selected = category, onSelect = vm::selectCategory)
        HomeFeed(
            vm = vm,
            actions = actions,
            sheet = sheet,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeHeader(onLogoClick: () -> Unit, onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(DsR.drawable.vidgrab_wordmark),
            contentDescription = "VidGrab. Search or paste a link",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(30.dp).pressable(scaleDown = 0.96f, role = Role.Button, onClick = onLogoClick),
        )
        Box(Modifier.weight(1f))
        QtIconButton(
            QtIcons.Search, "Search", onSearch,
            tint = cs.onSurface, container = cs.surfaceContainerHigh, size = 40.dp, iconSize = 20.dp,
        )
    }
}

@Composable
private fun HomeSearchBar(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .height(48.dp).clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .pressable(scaleDown = 0.98f, role = Role.Button, onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QtIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            "Search videos, music or paste a link",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
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
