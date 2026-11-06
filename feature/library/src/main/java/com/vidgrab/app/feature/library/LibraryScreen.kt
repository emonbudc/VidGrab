package com.vidgrab.app.feature.library

import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vidgrab.app.core.common.Format
import com.vidgrab.app.core.designsystem.IconTile
import com.vidgrab.app.core.designsystem.QtColors
import com.vidgrab.app.core.designsystem.signalInk
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.model.ContentType
import com.vidgrab.app.core.model.DownloadStatus
import com.vidgrab.app.core.model.DownloadTask
import com.vidgrab.app.core.ui.ConfirmDialog
import com.vidgrab.app.core.ui.EmptyBox
import com.vidgrab.app.core.ui.TextInputDialog
import org.koin.compose.viewmodel.koinViewModel
import java.io.File

@Composable
fun LibraryScreen(
    onOpenDownloading: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSource: (String) -> Unit,
    /** Saved / Watch later list. */
    onOpenSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = koinViewModel<LibraryViewModel>()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DownloadTask?>(null) }
    var renameFor by remember { mutableStateOf<DownloadTask?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Long>?>(null) }
    var filterOpen by remember { mutableStateOf(false) }

    // Re-import files from the download folder; needs media read access after a reinstall (Android 11+).
    val scan = {
        vm.importExisting { n ->
            Toast.makeText(context, if (n > 0) "Found $n files" else "No downloaded files found", Toast.LENGTH_SHORT).show()
        }
    }
    val mediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { scan() }

    // Files deleted by another app while VidGrab was away drop out of the list.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.recheckFiles(); onPauseOrDispose { } }

    BackHandler(selection != null || searching) { selection = null; searching = false; vm.query.value = "" }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        val sel = selection
        AnimatedContent(
            when { sel != null -> 1; searching -> 2; else -> 0 },
            transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "header",
        ) { mode ->
            when (mode) {
                1 -> Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val s = selection.orEmpty()
                    QtIconButton(QtIcons.Close, "Close", { selection = null })
                    Text("${s.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    QtIconButton(QtIcons.SelectAll, "Select all", { selection = if (s.size == ui.done.size) emptySet() else ui.done.map { it.id }.toSet() })
                    QtIconButton(QtIcons.Share, "Share", { FileActions.share(context, ui.done.filter { it.id in s }) }, enabled = s.isNotEmpty())
                    QtIconButton(QtIcons.Lock, "Vault", {
                        vm.toVault(s.toList()); selection = null
                        Toast.makeText(context, "Moved to vault", Toast.LENGTH_SHORT).show()
                    }, enabled = s.isNotEmpty())
                    QtIconButton(QtIcons.Delete, "Delete", { confirmDelete = s.toList() }, enabled = s.isNotEmpty(), tint = MaterialTheme.colorScheme.error)
                }
                2 -> {
                    val q by vm.query.collectAsStateWithLifecycle()
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        QtIconButton(QtIcons.ArrowBack, "Back", { searching = false; vm.query.value = "" })
                        TextField(
                            q, { vm.query.value = it }, Modifier.weight(1f).padding(end = 8.dp), placeholder = { Text("Search downloads") }, singleLine = true,
                            leadingIcon = { Icon(QtIcons.Search, null, Modifier.size(20.dp)) },
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            shape = RoundedCornerShape(4.dp),
                        )
                    }
                }
                else -> Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "LIBRARY / ON DEVICE",
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 8.sp,
                            letterSpacing = 1.1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Downloads",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp + LocalBottomBarHeight.current)) {
            if (ui.active.isNotEmpty() && sel == null && !searching) item {
                ActiveCard(ui.active.first(), ui.active.size, speeds[ui.active.first().id] ?: 0, onOpenDownloading)
            }
            if (sel == null && ui.done.isNotEmpty()) item(key = "downloaded-header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "DOWNLOADED · ${ui.done.size}",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    QtIconButton(QtIcons.BookmarkBorder, "Watch later", onOpenSaved, size = 38.dp, iconSize = 20.dp)
                    Box {
                        QtIconButton(QtIcons.FilterList, "Filter", { filterOpen = true }, size = 38.dp, iconSize = 20.dp)
                        DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                            LibraryFilter.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.label, fontWeight = if (ui.filter == f) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { vm.filter.value = f; filterOpen = false },
                                )
                            }
                        }
                    }
                    QtIconButton(QtIcons.Search, "Search", { searching = true }, size = 38.dp, iconSize = 20.dp)
                    QtIconButton(QtIcons.DeleteSweep, "Delete all", {
                        if (ui.done.isNotEmpty()) confirmDelete = ui.done.map { it.id }
                    }, size = 38.dp, iconSize = 20.dp)
                }
            }
            if (ui.loaded && ui.done.isEmpty()) item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyBox(
                        if (ui.query.isNotBlank()) "No matches" else "No downloads yet",
                        if (ui.query.isNotBlank()) "Try another name" else "Videos and music you download appear here",
                        Modifier.fillMaxWidth().padding(top = 48.dp), QtIcons.VideoLibrary,
                    )
                    if (ui.query.isBlank() && ui.filter == LibraryFilter.ALL) com.vidgrab.app.core.designsystem.PillButton(
                        "Find my downloads", onClick = {
                            if (com.vidgrab.app.core.common.Permissions.mediaUsable(context)) scan()
                            else mediaLauncher.launch(com.vidgrab.app.core.common.Permissions.mediaRead)
                        }, icon = QtIcons.Search, secondary = true,
                    )
                }
            }
            items(ui.done, key = { it.id }) { task ->
                Box(Modifier.animateItem()) { DoneRow(
                    task,
                    watched = ui.watched[task.filePath],
                    selected = sel?.contains(task.id),
                    onClick = {
                        if (sel != null) selection = if (task.id in sel) sel - task.id else sel + task.id
                        else if (task.type == ContentType.VIDEO || task.type == ContentType.AUDIO) { vm.play(task); onOpenPlayer() }
                        else FileActions.openWith(context, task)
                    },
                    onLongClick = { if (sel == null) selection = setOf(task.id) },
                    onMore = { menuFor = task },
                ) }
            }
        }
    }

    menuFor?.let { task ->
        ItemMenu(
            task,
            onDismiss = { menuFor = null },
            onVault = { vm.toVault(listOf(task.id)); Toast.makeText(context, "Moved to vault", Toast.LENGTH_SHORT).show() },
            onConvert = {
                Toast.makeText(context, "Converting…", Toast.LENGTH_SHORT).show()
                vm.convertToAudio(task) { ok -> Toast.makeText(context, if (ok) "Saved as audio" else "Conversion failed", Toast.LENGTH_SHORT).show() }
            },
            onListen = { vm.play(task, audioOnly = true); onOpenPlayer() },
            onShare = { FileActions.share(context, listOf(task)) },
            onOpenWith = { FileActions.openWith(context, task) },
            onRename = { renameFor = task },
            onDelete = { confirmDelete = listOf(task.id) },
            onSource = { task.pageUrl?.let(onOpenSource) },
        )
    }
    renameFor?.let { task ->
        TextInputDialog("Rename", task.title, "File name", "Save", onConfirm = { name ->
            vm.rename(task.id, name) { ok -> if (!ok) Toast.makeText(context, "Couldn't rename", Toast.LENGTH_SHORT).show() }
        }, onDismiss = { renameFor = null })
    }
    confirmDelete?.let { ids ->
        ConfirmDialog(
            "Delete ${if (ids.size > 1) "${ids.size} files" else "file"}?",
            "Deleted files stay in the recycle bin for 30 days.",
            "Delete",
            onConfirm = { vm.delete(ids); selection = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Thin blue progress bar used for both active downloads and watched progress. */
@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier, indeterminate: Boolean = false) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))) {
        if (!indeterminate) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun ActiveCard(task: DownloadTask, count: Int, speed: Long, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .pressable(scaleDown = 0.985f, onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "TRANSFER / $count ACTIVE",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                    color = signalInk(QtColors.LiveMint),
                )
                Text("Downloading", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(task, Modifier.width(112.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                GradientProgress(task.progress, Modifier.fillMaxWidth().padding(top = 7.dp))
                Row(Modifier.padding(top = 5.dp)) {
                    Text(statusText(task, speed), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
                        color = when {
                            task.status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            task.status == DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${(task.progress * 100).toInt()}%", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
/** "1.0 / 5.7 GB" (one unit when both share it) so the status line fits small screens. */
private fun compactProgress(done: Long, total: Long?): String {
    val d = Format.bytes(done)
    if (total == null) return d
    val t = Format.bytes(total)
    val du = d.substringAfterLast(' ')
    return if (du == t.substringAfterLast(' ')) d.substringBeforeLast(' ') + " / " + t else "$d / $t"
}

fun statusText(task: DownloadTask, speed: Long): String = when (task.status) {
    DownloadStatus.RUNNING -> Format.speed(speed) + " · " +
        compactProgress(task.downloadedBytes + task.audioDownloadedBytes, task.totalBytes.takeIf { it > 0 }?.plus(task.audioTotalBytes.coerceAtLeast(0)))
    DownloadStatus.QUEUED -> "Waiting…"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.WAITING_NETWORK -> "Waiting for network"
    DownloadStatus.MERGING -> "Processing video…"
    DownloadStatus.FAILED -> com.vidgrab.app.core.common.DownloadErrors.friendly(task.error)
    DownloadStatus.COMPLETED -> "Completed"
}

@Composable
fun FileThumb(task: DownloadTask, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val file = File(task.filePath)
        val model: Any? = when {
            task.type == ContentType.IMAGE && file.exists() -> file
            task.thumbnail != null -> task.thumbnail
            task.type == ContentType.VIDEO && file.exists() -> file
            else -> null
        }
        Icon(if (task.type == ContentType.AUDIO) QtIcons.MusicNote else QtIcons.PlayCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (task.type == ContentType.AUDIO && model != null) Box(
            Modifier.align(Alignment.BottomStart).padding(5.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.MusicNote, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
        if (task.durationSec > 0 && task.type == ContentType.VIDEO) Text(
            Format.duration(task.durationSec), color = Color(0xFFE7E9EE),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(0xCC0D0F13)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun DoneRow(task: DownloadTask, watched: Float?, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(task, Modifier.width(112.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.Play, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    listOf(if (task.durationSec > 0) Format.duration(task.durationSec) else "", Format.bytes(task.totalBytes))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val progress = watched ?: if (task.lastPlayedPositionMs > 0 && task.durationSec > 0) task.lastPlayedPositionMs / 1000f / task.durationSec else null
            if (progress != null && progress > 0f) GradientProgress(
                progress.coerceIn(0f, 1f),
                Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(3.dp),
            )
        }
        if (selected != null) Box(
            Modifier.padding(end = 10.dp).size(22.dp).clip(RoundedCornerShape(4.dp))
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(QtIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
        else QtIconButton(QtIcons.MoreVert, "More", onMore, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemMenu(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onVault: () -> Unit,
    onConvert: () -> Unit,
    onListen: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSource: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(task, Modifier.width(100.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                task.sourceHost?.let {
                    Text(it.uppercase(), color = signalInk(QtColors.LiveMint), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 8.sp, letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(2.dp)).pressable(onClick = { onSource(); onDismiss() }))
                }
            }
            QtIconButton(QtIcons.Edit, "Rename", { onRename(); onDismiss() }, container = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        fun go(a: () -> Unit) = { a(); onDismiss() }
        MenuRow(QtIcons.Lock, "Lock into Vault", Color(0xFFFF5C93), go(onVault))
        if (task.type == ContentType.VIDEO) {
            MenuRow(QtIcons.Headphones, "Play as audio", Color(0xFF9B7BFF), go(onListen))
            MenuRow(QtIcons.AudioFile, "Convert to audio", Color(0xFFFF8A3D), go(onConvert))
        }
        MenuRow(QtIcons.Share, "Share", signalInk(QtColors.Success), go(onShare))
        MenuRow(QtIcons.OpenInNew, "Open with other app", signalInk(QtColors.LiveBlue), go(onOpenWith))
        MenuRow(QtIcons.Delete, "Delete file", MaterialTheme.colorScheme.error, go(onDelete))
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = tint, size = 38.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
    }
}
