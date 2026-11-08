package com.vidgrab.app.feature.video

import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.vidgrab.app.core.ui.LoadingBox
import com.vidgrab.app.core.designsystem.shimmer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.vidgrab.app.core.ui.MediaRow
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
    val context = LocalContext.current

    FullscreenEffect(fullscreen && !LocalPipMode.current)
    BackHandler(fullscreen) { fullscreen = false }
    val saved by vm.savedFlow.collectAsStateWithLifecycle()
    // The page's video may already be playing (the mini player sent it back here): draw it at once, details or not.
    val pageItemId = (details as? LoadState.Success)?.data?.item?.let { "web:" + it.url } ?: "web:$url"
    val playingThis = playback.current?.id == pageItemId
    if (details is LoadState.Success || playingThis) AutoPipEffect(vm.player, playback)
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
    // YouTube's player extras: Quality, the autoplay switch, Next = the first suggestion (autoplay starts it instantly).
    val qualityMenu = vm.qualities().takeIf { it.isNotEmpty() }?.let { q ->
        QualityMenu(if (currentQuality == "Auto") vm.autoLabel() else currentQuality, q) { vm.setQuality(it) }
    }
    val nextVideo: (() -> Unit)? = related?.let { r -> { onPlayNext(r) } }
    val upNext = related?.let { r -> UpNext { onPlayNext(r) } }
    val onAutoplay: (Boolean) -> Unit = { v -> scope.launch { settingsRepo.update { it.copy(autoplayNext = v) } } }
    val playerOptions = listOf(
        PlayerOption(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, if (saved) "Saved to Watch later" else "Watch later", saved) {
            vm.toggleSaved()
            Toast.makeText(context, if (saved) "Removed from Watch later" else "Saved to Watch later", Toast.LENGTH_SHORT).show()
        },
        PlayerOption(
            QtIcons.Headphones,
            if (appSettings.backgroundPlay) "Background play on" else "Play in background",
            appSettings.backgroundPlay,
        ) {
            val turnOn = !appSettings.backgroundPlay
            scope.launch { settingsRepo.update { it.copy(backgroundPlay = turnOn) } }
            if (turnOn) vm.playInBackground()
            Toast.makeText(
                context,
                if (turnOn) "Sound keeps playing after leaving the app" else "Background play off",
                Toast.LENGTH_SHORT,
            ).show()
        },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (details is LoadState.Loading && !playingThis) PlayerLoading(previewThumbnail)
            }
            when (val d = details) {
                is LoadState.Loading -> VideoPageSkeleton(previewTitle, Modifier.fillMaxWidth().weight(1f))
                is LoadState.Error -> ErrorBox(d.message, vm::retry, Modifier.fillMaxWidth().weight(1f))
                is LoadState.Success -> {
                    val v = d.data
                    LazyColumn(Modifier.weight(1f), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                        item {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).clickable { showAbout = true }) {
                                Text(v.item.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(Format.views(v.item.viewCount), v.uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 7.dp),
                                )
                            }
                        }
                        item {
                            VideoActions(
                                v = v,
                                saved = saved,
                                audioOnly = playback.audioOnly,
                                onDownload = { sheet.open(v.item.url, v.item) },
                                onSave = {
                                    vm.toggleSaved()
                                    Toast.makeText(context, if (saved) "Removed from Watch later" else "Saved to Watch later", Toast.LENGTH_SHORT).show()
                                },
                                onListen = {
                                    val listening = playback.audioOnly
                                    vm.toggleBackground()
                                    Toast.makeText(
                                        context,
                                        if (listening) "Video back on" else "Listening in the background",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onShare = {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, v.item.url), "Share"))
                                },
                                onPip = {
                                    vm.player.setAudioOnly(false)
                                    leave(toPip = true)
                                },
                                onMore = { showAbout = true },
                            )
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
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                Icon(QtIcons.ChevronRight, "Open channel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { SectionHeader("Up next") }
                        items(v.related, key = { it.url }) { r ->
                            MediaRow(r, onClick = { onOpen(r) }, onDownload = { sheet.open(r) })
                        }
                    }
                    if (showAbout) AboutSheet(v, vm, onDismiss = { showAbout = false }, onChannel = onChannel)
                }
            }
        }
        if (details is LoadState.Success || playingThis) {
            Box(
                Modifier.align(Alignment.TopCenter).background(Color.Black).then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f),
                ),
            ) {
                VideoPlayerBox(
                    vm.player, playback, Modifier.fillMaxSize(), fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen }, onCollapse = if (fullscreen) { { fullscreen = false } } else null,
                    showPrevNext = false, onRetry = vm::retry, options = playerOptions, onPip = { vm.player.setAudioOnly(false); leave(toPip = true) },
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
private fun VideoActions(
    v: VideoDetails,
    saved: Boolean,
    audioOnly: Boolean,
    onDownload: () -> Unit,
    onSave: () -> Unit,
    onListen: () -> Unit,
    onShare: () -> Unit,
    onPip: () -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val plainDescription = remember(v.description) {
        v.description.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
    }
    fun saveThumb() = scope.launch {
        val name = saveThumbnail(context, v.item.thumbnail, v.item.title)
        Toast.makeText(
            context,
            if (name != null) "Thumbnail saved to Pictures/VidGrab" else "Couldn't save the thumbnail",
            Toast.LENGTH_SHORT,
        ).show()
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.vidgrab.app.core.designsystem.PillButton(
                "Download", onDownload, Modifier.weight(1f), icon = QtIcons.Download, enabled = !v.item.isLive,
            )
            QtIconButton(
                QtIcons.MoreHoriz, "More actions", onMore, size = 48.dp,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(QtIcons.Image, "Thumbnail", onClick = { saveThumb() }, modifier = Modifier.weight(1f))
            ActionTile(QtIcons.ContentCopy, "Copy title", onClick = { copyText(context, "Title", v.item.title) }, modifier = Modifier.weight(1f))
            ActionTile(QtIcons.ContentCopy, "Description", onClick = { copyText(context, "Description", plainDescription) }, modifier = Modifier.weight(1f))
            ActionTile(
                QtIcons.Link, "Tags", enabled = v.tags.isNotEmpty(),
                onClick = { copyText(context, "Tags", v.tags.joinToString("\n") { "#$it" }) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(
                if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder,
                if (saved) "Saved" else "Watch later",
                selected = saved, onClick = onSave, modifier = Modifier.weight(1f),
            )
            ActionTile(
                if (audioOnly) QtIcons.Videocam else QtIcons.Headphones,
                if (audioOnly) "Watch" else "Listen",
                selected = audioOnly, onClick = onListen, modifier = Modifier.weight(1f),
            )
            ActionTile(QtIcons.Share, "Share", onClick = onShare, modifier = Modifier.weight(1f))
            ActionTile(QtIcons.PictureInPicture, "PiP", onClick = onPip, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.surfaceContainerHigh)
            .pressable(enabled = enabled, scaleDown = 0.95f, onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon, null,
            tint = when {
                !enabled -> cs.onSurfaceVariant.copy(alpha = 0.38f)
                selected -> cs.primary
                else -> cs.onSurface
            },
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = when {
                !enabled -> cs.onSurfaceVariant.copy(alpha = 0.38f)
                selected -> cs.primary
                else -> cs.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun AboutSheet(v: VideoDetails, vm: VideoViewModel, onDismiss: () -> Unit, onChannel: (String) -> Unit) {
    val context = LocalContext.current
    val saved by vm.savedFlow.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val plainDescription = remember(v.description) {
        v.description.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
    }
    fun saveThumb() = scope.launch {
        val name = saveThumbnail(context, v.item.thumbnail, v.item.title)
        Toast.makeText(
            context,
            if (name != null) "Thumbnail saved to Pictures/VidGrab" else "Couldn't save the thumbnail",
            Toast.LENGTH_SHORT,
        ).show()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("About", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(v.item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(QtIcons.MoreVert, "More actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Save thumbnail") },
                            onClick = { menuOpen = false; saveThumb() },
                            leadingIcon = { Icon(QtIcons.Image, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy title") },
                            onClick = { menuOpen = false; copyText(context, "Title", v.item.title) },
                            leadingIcon = { Icon(QtIcons.ContentCopy, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy description") },
                            onClick = { menuOpen = false; copyText(context, "Description", plainDescription) },
                            leadingIcon = { Icon(QtIcons.ContentCopy, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (v.tags.isEmpty()) "No tags" else "Copy tags") },
                            enabled = v.tags.isNotEmpty(),
                            onClick = { menuOpen = false; copyText(context, "Tags", v.tags.joinToString("\n") { "#$it" }) },
                            leadingIcon = { Icon(QtIcons.Link, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy link") },
                            onClick = { menuOpen = false; copyText(context, "Link", v.item.url) },
                            leadingIcon = { Icon(QtIcons.Link, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Open in browser") },
                            onClick = {
                                menuOpen = false
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(v.item.url))) }
                            },
                            leadingIcon = { Icon(QtIcons.OpenInNew, null) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { v.item.uploaderUrl?.let(onChannel); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(v.item.uploaderAvatar, 40)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(v.item.uploader, fontWeight = FontWeight.SemiBold)
                    if (v.subscriberCount >= 0) Text("${Format.count(v.subscriberCount)} subscribers", style = MaterialTheme.typography.labelSmall,
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
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp),
            )
            Text(plainDescription,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            if (v.tags.isNotEmpty()) {
                Text(
                    v.tags.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
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
 * and the "Up next" list, all as soft shimmering blocks.
 */
@Composable
private fun VideoPageSkeleton(title: String?, modifier: Modifier) {
    val bone = MaterialTheme.colorScheme.surfaceContainerHigh
    fun Modifier.bone(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) = clip(shape).background(bone).shimmer()
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
            Box(Modifier.weight(1f).height(48.dp).bone(RoundedCornerShape(12.dp)))
            repeat(3) { Box(Modifier.padding(start = 10.dp).size(48.dp).bone(RoundedCornerShape(12.dp))) }
        }
        Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp).width(80.dp).height(16.dp).bone())
        repeat(5) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(160.dp).aspectRatio(16f / 9f).bone(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp).bone())
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.8f).height(14.dp).bone())
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).height(11.dp).bone())
                }
            }
        }
    }
}
