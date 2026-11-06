package com.vidgrab.app.feature.video

import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.Format
import com.vidgrab.app.core.common.LoadState
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.designsystem.SectionHeader
import com.vidgrab.app.core.downloadsheet.DownloadSheetController
import com.vidgrab.app.core.model.Comment
import com.vidgrab.app.core.model.MediaItem
import com.vidgrab.app.core.model.VideoDetails
import com.vidgrab.app.core.playerui.FullscreenEffect
import com.vidgrab.app.core.playerui.PlayerOption
import com.vidgrab.app.core.playerui.QualityMenu
import com.vidgrab.app.core.playerui.UpNext
import kotlinx.coroutines.launch
import com.vidgrab.app.core.playerui.AutoPipEffect
import com.vidgrab.app.core.playerui.LocalPipMode
import com.vidgrab.app.core.playerui.VideoPlayerBox
import com.vidgrab.app.core.ui.Avatar
import com.vidgrab.app.core.ui.ErrorBox
import com.vidgrab.app.core.ui.FooterLoading
import com.vidgrab.app.core.ui.LoadingBox
import com.vidgrab.app.core.designsystem.shimmer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.vidgrab.app.core.ui.MediaRow
import com.vidgrab.app.core.ui.OnNearEnd
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VideoScreen(
    url: String,
    /** The tapped card's title and thumbnail, shown while the page loads. */
    previewTitle: String? = null,
    previewThumbnail: String? = null,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onChannel: (String) -> Unit,
    /** The player's picture-in-picture button: the video goes on in the floating window. */
    onPip: () -> Unit = {},
    /** Plays another video in place of this one (Next, autoplay). */
    onPlayNext: (MediaItem) -> Unit = onOpen,
) {
    val vm = koinViewModel<VideoViewModel>(key = url)
    LaunchedEffect(url) { vm.load(url) }
    val details by vm.details.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val sheet = koinInject<DownloadSheetController>()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val context = LocalContext.current

    FullscreenEffect(fullscreen && !LocalPipMode.current)
    BackHandler(fullscreen) { fullscreen = false }
    val saved by vm.savedFlow.collectAsStateWithLifecycle()
    if (details is LoadState.Success) AutoPipEffect(vm.player, playback)
    // Back closes the video; only the picture-in-picture button sends it to the floating window.
    fun leave(toPip: Boolean = false) {
        if (toPip) { onPip(); return }
        onBack()
    }
    BackHandler(!fullscreen) { leave() }
    if (LocalPipMode.current) {
        // The whole app is the picture-in-picture window: only the video.
        VideoPlayerBox(vm.player, playback, Modifier.fillMaxSize(), fullscreen = true, showPrevNext = false, overlayControls = false)
        return
    }
    // Rows this page adds to the player's options sheet.
    val settingsRepo = koinInject<com.vidgrab.app.core.data.SettingsRepository>()
    val appSettings by settingsRepo.settings.collectAsStateWithLifecycle(com.vidgrab.app.core.model.AppSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val currentQuality by vm.quality.collectAsStateWithLifecycle()
    val related = (details as? LoadState.Success)?.data?.related.orEmpty().firstOrNull { it.kind == com.vidgrab.app.core.model.ItemKind.VIDEO && !it.isLive }
    // YouTube's player extras: Quality, the autoplay switch, Next = the first suggestion (with "Up next" at the end).
    val qualityMenu = vm.qualities().takeIf { it.isNotEmpty() }?.let { q ->
        QualityMenu(if (currentQuality == "Auto") vm.autoLabel() else currentQuality, q) { vm.setQuality(it) }
    }
    val nextVideo: (() -> Unit)? = related?.let { r -> { onPlayNext(r) } }
    val upNext = related?.let { r -> UpNext(r.title, r.thumbnail) { onPlayNext(r) } }
    val onAutoplay: (Boolean) -> Unit = { v -> scope.launch { settingsRepo.update { it.copy(autoplayNext = v) } } }
    val playerOptions = listOf(
        PlayerOption(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, if (saved) "Saved to Watch later" else "Watch later", saved) {
            vm.toggleSaved()
            Toast.makeText(context, if (saved) "Removed from Watch later" else "Saved to Watch later", Toast.LENGTH_SHORT).show()
        },
        PlayerOption(QtIcons.Headphones, "Play in background") {
            vm.playInBackground()
            Toast.makeText(context, "Playing audio in background", Toast.LENGTH_SHORT).show()
        },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (details is LoadState.Loading) PlayerLoading(previewThumbnail)
            }
            when (val d = details) {
                is LoadState.Loading -> VideoPageSkeleton(previewTitle, Modifier.fillMaxWidth().weight(1f))
                is LoadState.Error -> ErrorBox(d.message, vm::retry, Modifier.fillMaxWidth().weight(1f))
                is LoadState.Success -> {
                    val v = d.data
                    val comments by vm.comments.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                        item {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).clickable { showAbout = true }) {
                                Text(v.item.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(Format.views(v.item.viewCount), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 7.dp),
                                )
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                WatchAction(QtIcons.Download, "Download", filled = true, enabled = !v.item.isLive) {
                                    sheet.open(v.item.url, v.item)
                                }
                                WatchAction(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, if (saved) "Saved" else "Watch later", selected = saved) {
                                    vm.toggleSaved()
                                    Toast.makeText(context, if (saved) "Removed from Watch later" else "Saved to Watch later", Toast.LENGTH_SHORT).show()
                                }
                                WatchAction(QtIcons.Headphones, if (playback.audioOnly) "Listening" else "Listen", selected = playback.audioOnly) {
                                    vm.playInBackground()
                                    Toast.makeText(context, "Playing audio in background", Toast.LENGTH_SHORT).show()
                                }
                                WatchAction(QtIcons.Share, "Share") {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, v.item.url), "Share"))
                                }
                                WatchAction(QtIcons.MoreHoriz, "More") { showAbout = true }
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { v.item.uploaderUrl?.let(onChannel) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(v.item.uploaderAvatar, 40)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(v.item.uploader, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (v.subscriberCount >= 0) "${Format.count(v.subscriberCount)} subscribers" else "YouTube channel",
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                Icon(QtIcons.ChevronRight, "Open channel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable { showComments = true }.padding(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("COMMENTS", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 9.sp,
                                        letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                    Text("VIEW ALL", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                val preview = comments.items.take(2)
                                if (preview.isEmpty()) Text(
                                    if (comments.loading) "Loading…" else comments.error?.let { "Comments unavailable" } ?: "No comments",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                preview.forEach { c ->
                                    Row(Modifier.padding(top = 10.dp)) {
                                        Avatar(c.authorAvatar, 26)
                                        Column(Modifier.padding(start = 10.dp)) {
                                            Text(c.author, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                        item { SectionHeader("Up next") }
                        items(v.related, key = { it.url }) { r ->
                            MediaRow(r, onClick = { onOpen(r) }, onDownload = { sheet.open(r) })
                        }
                    }
                    if (showAbout) AboutSheet(v, vm, onDismiss = { showAbout = false }, onChannel = onChannel)
                    if (showComments) CommentsSheet(vm, onDismiss = { showComments = false })
                }
            }
        }
        if (details is LoadState.Success) {
            Box(
                Modifier.align(Alignment.TopCenter).background(Color.Black).then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f),
                ),
            ) {
                VideoPlayerBox(
                    vm.player, playback, Modifier.fillMaxSize(), fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen }, onCollapse = if (fullscreen) { { fullscreen = false } } else null,
                    showPrevNext = false, onRetry = vm::retry, options = playerOptions, onPip = { leave(toPip = true) },
                    quality = qualityMenu, autoplay = appSettings.autoplayNext, onAutoplayChange = onAutoplay, onNext = nextVideo, upNext = upNext,
                )
            }
        }
        if (!fullscreen) {
            IconButton(onClick = { leave() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
                Icon(QtIcons.ArrowBack, "Back", tint = Color.White)
            }
        }
    }
}

@Composable
private fun WatchAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val background = when {
        !enabled -> cs.surfaceContainerHigh
        filled -> cs.primary
        selected -> cs.primary.copy(alpha = 0.14f)
        else -> cs.surfaceContainerHigh
    }
    val content = when {
        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f)
        filled -> cs.onPrimary
        selected -> cs.primary
        else -> cs.onSurface
    }
    Row(
        Modifier.height(42.dp).clip(RoundedCornerShape(4.dp)).background(background)
            .pressable(enabled = enabled, scaleDown = 0.94f, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun AboutSheet(v: VideoDetails, vm: VideoViewModel, onDismiss: () -> Unit, onChannel: (String) -> Unit) {
    val context = LocalContext.current
    val saved by vm.savedFlow.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("ABOUT", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v.item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().clickable { v.item.uploaderUrl?.let(onChannel); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(v.item.uploaderAvatar, 40)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(v.item.uploader, fontWeight = FontWeight.SemiBold)
                    if (v.subscriberCount >= 0) Text("${Format.count(v.subscriberCount)} subscribers", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(vertical = 8.dp)) {
                TextButton(onClick = {}) {
                    Icon(QtIcons.ThumbUp, null); Text(" " + if (v.likeCount >= 0) Format.count(v.likeCount) else "Like")
                }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, v.item.url), "Share"))
                }) { Icon(QtIcons.Share, null); Text(" Share") }
                TextButton(onClick = vm::toggleSaved) {
                    Icon(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, null)
                    Text(if (saved) " Saved" else " Watch later")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                listOf(Format.views(v.item.viewCount), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString("  "),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp, modifier = Modifier.padding(top = 12.dp),
            )
            Text(v.description.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun CommentsSheet(vm: VideoViewModel, onDismiss: () -> Unit) {
    val ui by vm.comments.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Text("COMMENTS", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val state = rememberLazyListState()
        state.OnNearEnd { vm.loadComments() }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 640.dp)) {
            items(ui.items) { CommentRow(it) }
            if (ui.loading) item { FooterLoading() }
            if (!ui.loading && ui.items.isEmpty()) item { Text("No comments", Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun CommentRow(c: Comment) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Avatar(c.authorAvatar, 32)
        Column(Modifier.padding(start = 12.dp)) {
            Text(listOfNotNull(c.author, c.date).joinToString(" · "), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.ThumbUp, null, Modifier.padding(end = 4.dp).heightIn(max = 14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (c.likeCount > 0) Format.count(c.likeCount.toLong()) else "", style = MaterialTheme.typography.labelSmall)
                if (c.replyCount > 0) Text("   ${c.replyCount} replies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The player area while the page loads: the video's thumbnail (when known) under the loading ring. */
@Composable
private fun PlayerLoading(thumbnail: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (thumbnail != null) coil3.compose.AsyncImage(
            thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), alpha = 0.55f,
        )
        com.vidgrab.app.core.playerui.PlayerSpinner()
    }
}

/**
 * The page's shape while it loads: title (the real one when the tapped card had it), channel, the action buttons,
 * comments and the "Up next" list, all as soft shimmering blocks.
 */
@Composable
private fun VideoPageSkeleton(title: String?, modifier: Modifier) {
    val bone = MaterialTheme.colorScheme.surfaceContainerHigh
    fun Modifier.bone(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp)) = clip(shape).background(bone).shimmer()
    Column(modifier.verticalScroll(rememberScrollState(), enabled = false)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).bone(androidx.compose.foundation.shape.CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else {
                    Box(Modifier.fillMaxWidth().height(18.dp).bone())
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.7f).height(18.dp).bone())
                }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.55f).height(12.dp).bone())
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(48.dp).bone(RoundedCornerShape(4.dp)))
            repeat(3) { Box(Modifier.padding(start = 10.dp).size(48.dp).bone(RoundedCornerShape(4.dp))) }
        }
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(76.dp).bone(RoundedCornerShape(4.dp)))
        Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp).width(80.dp).height(16.dp).bone())
        repeat(5) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(160.dp).aspectRatio(16f / 9f).bone(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp).bone())
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.8f).height(14.dp).bone())
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).height(11.dp).bone())
                }
            }
        }
    }
}
