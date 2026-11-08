package com.vidgrab.app.feature.settings

import com.vidgrab.app.core.designsystem.LocalBottomBarHeight
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.designsystem.icon.BrandIcons
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.model.ThemeMode
import org.koin.compose.viewmodel.koinViewModel

class SettingsActions(
    val downloadSettings: () -> Unit,
    val playerSettings: () -> Unit,
    val notificationSettings: () -> Unit,
    val theme: () -> Unit,
    val recycleBin: () -> Unit,
    val deletedHistory: () -> Unit,
    val statusSaver: () -> Unit,
    val vault: () -> Unit,
    val storageClean: () -> Unit,
    val largeFiles: () -> Unit,
    val appManager: () -> Unit,
    val region: () -> Unit,
    val about: () -> Unit,
)


@Composable
fun SettingsScreen(actions: SettingsActions) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    val trash by vm.trashCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val updates = org.koin.compose.koinInject<com.vidgrab.app.core.update.UpdateManager>()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val updateSummary = when (updateState) {
        is com.vidgrab.app.core.update.UpdateState.Checking -> "Checking…"
        is com.vidgrab.app.core.update.UpdateState.UpToDate -> "You're on the latest version"
        is com.vidgrab.app.core.update.UpdateState.Failed -> "Couldn't check. Tap to try again"
        is com.vidgrab.app.core.update.UpdateState.Downloading -> (updateState as com.vidgrab.app.core.update.UpdateState.Downloading)
            .let { d -> "Downloading update… " + if (d.total > 0) "${d.bytes * 100 / d.total}%" else "" }
        is com.vidgrab.app.core.update.UpdateState.Installing -> "Installing update…"
        else -> "Version ${appVersion(context)}"
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarHeight.current)) {
        item {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    "Appearance, downloads, storage and privacy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        item { GroupLabel("General") }
        item {
            com.vidgrab.app.core.designsystem.QtCard {
                NavRow(QtIcons.Download, "Download settings", "Path, speed limit, tasks", onClick = actions.downloadSettings)
                RowDivider()
                NavRow(QtIcons.PlayCircle, "Player", "Resume, history, gestures, picture-in-picture", onClick = actions.playerSettings)
                RowDivider()
                NavRow(QtIcons.Notifications, "Notification settings", onClick = actions.notificationSettings)
                RowDivider()
                NavRow(QtIcons.Palette, "Theme", trailing = when (s.themeMode) {
                    ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"
                }, onClick = actions.theme)
                RowDivider()
                NavRow(QtIcons.Public, "Content region", trailing = s.contentRegion.ifBlank { "Auto" }, onClick = actions.region)
                RowDivider()
                SwitchRow("Trending on home", "Show trending videos on Home", s.showTrending, QtIcons.TrendingUp) { v ->
                    vm.update { it.copy(showTrending = v) }
                }
                RowDivider()
                SwitchRow("Clipboard detection", "Offer a copied link when you open the app", s.clipboardDetection, QtIcons.ContentPaste) { v ->
                    vm.update { it.copy(clipboardDetection = v) }
                }
            }
        }
        item { GroupLabel("Download tools") }
        item {
            com.vidgrab.app.core.designsystem.QtCard {
                NavRow(QtIcons.RestoreFromTrash, "Recover deleted files", trailing = if (trash > 0) "$trash" else null, onClick = actions.recycleBin)
                RowDivider()
                NavRow(QtIcons.History, "Deleted downloads", "Download again anything no longer on this phone", onClick = actions.deletedHistory)
                RowDivider()
                NavRow(BrandIcons.WhatsApp, "WhatsApp status saver", onClick = actions.statusSaver)
                RowDivider()
                NavRow(QtIcons.Lock, "Vault", "Hide private files behind a PIN", onClick = actions.vault)
            }
        }
        item { GroupLabel("Phone clean") }
        item {
            com.vidgrab.app.core.designsystem.QtCard {
                NavRow(QtIcons.CleaningServices, "Storage clean", "Temporary files, cache and leftovers", onClick = actions.storageClean)
                RowDivider()
                NavRow(QtIcons.FolderZip, "Large files clean", "Find big videos, music and photos", onClick = actions.largeFiles)
                RowDivider()
                NavRow(QtIcons.Apps, "App uninstaller", onClick = actions.appManager)
            }
        }
        item { GroupLabel("Privacy") }
        item {
            com.vidgrab.app.core.designsystem.QtCard {
                SwitchRow(
                    "Share crash reports & usage", "Anonymous: never URLs, titles, searches or files. See PRIVACY.md",
                    s.analyticsEnabled, QtIcons.Shield,
                ) { v -> vm.update { it.copy(analyticsEnabled = v) } }
            }
        }
        item { GroupLabel("Info") }
        item {
            com.vidgrab.app.core.designsystem.QtCard {
                NavRow(QtIcons.Feedback, "Feedback") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/emonbudc/VidGrab/issues")))
                }
                RowDivider()
                NavRow(QtIcons.Share, "Share VidGrab") {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                        Intent.EXTRA_TEXT, "Download videos and music with VidGrab: https://github.com/emonbudc/VidGrab",
                    ), "Share VidGrab"))
                }
                RowDivider()
                NavRow(QtIcons.Downloading, "Check for updates", updateSummary) { updates.checkOrShow() }
                RowDivider()
                NavRow(QtIcons.Info, "About", trailing = "v" + appVersion(context), onClick = actions.about)
            }
        }
    }
}

fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
