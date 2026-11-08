package com.vidgrab.app.core.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.Format
import com.vidgrab.app.core.designsystem.LocalQtExtras
import com.vidgrab.app.core.designsystem.PillButton
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import org.koin.compose.koinInject
import com.vidgrab.app.core.designsystem.R as DsR

/** Place once at the root: shows the "Update available" sheet and walks through download → permission → install. */
@Composable
fun UpdatePrompt() {
    val manager = koinInject<UpdateManager>()
    val visible by manager.promptVisible.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Coming back from Android's "Install unknown apps" screen continues the update by itself (also after Android
    // restarted the app for that permission change).
    LifecycleResumeEffect(Unit) {
        manager.resumeIfPending()
        onPauseOrDispose {}
    }
    // "Update now" without the install permission goes straight to Android's switch for it.
    val update = { i: UpdateInfo ->
        if (!manager.start(i)) runCatching { context.startActivity(manager.permissionIntent()) }
        Unit
    }
    if (!visible) return
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    } ?: return
    // Closing the sheet never stops anything: a download goes on in the background (notification bar).
    ModalBottomSheet(
        onDismissRequest = { manager.later() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        ) {
            BrandCard(info)
            Text(
                titleFor(state),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 22.dp),
            )
            Text(
                subtitleFor(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            VersionRoute(manager.currentVersionName, info.versionName, Modifier.padding(top = 18.dp))
            if (state !is UpdateState.Downloading && state !is UpdateState.Installing) ReleaseNotes(info.notes)
            if (state is UpdateState.Available || state is UpdateState.NeedsPermission) VerifiedNote(info)
            Box(Modifier.padding(top = 20.dp)) {
                AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "update") { s ->
                    when (s) {
                        is UpdateState.Downloading -> Column {
                            Progress(
                                if (s.total > 0) s.bytes.toFloat() / s.total else 0f,
                                "Downloading… " + Format.bytes(s.bytes) + if (s.total > 0) " of ${Format.bytes(s.total)}" else "",
                            )
                            Text(
                                "You can keep using VidGrab: the download goes on in the background, with its progress in the notification bar.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                            PillButton("Continue in background", { manager.later() }, Modifier.fillMaxWidth().padding(top = 16.dp), secondary = true)
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { TextAction("Cancel download") { manager.cancel() } }
                        }
                        is UpdateState.Installing -> Column {
                            Progress(1f, if (s.needsTap) "Ready to install" else "Installing…")
                            if (s.needsTap) PillButton("Install", { manager.confirmInstall() }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = QtIcons.Download)
                            Text(
                                if (s.needsTap) "Android shows the install screen: tap Install, then Open when it's done. If you closed it, tap Install here."
                                else "Android is installing the update. VidGrab closes meanwhile; tap Open on Android's screen to come back.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                        is UpdateState.NeedsPermission -> Column {
                            Text(
                                "Android needs your OK once to let VidGrab install its updates. Turn on " +
                                    "\"Allow from this source\", then come back: the update continues by itself.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PillButton("Allow installs", { context.startActivity(manager.permissionIntent()) },
                                Modifier.fillMaxWidth().padding(top = 16.dp), icon = QtIcons.Shield)
                            Later(manager)
                        }
                        is UpdateState.Failed -> Column {
                            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            PillButton("Try again", { update(info) }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = QtIcons.Refresh)
                            Later(manager)
                        }
                        else -> Column {
                            PillButton("Update to v${info.versionName}", { update(info) }, Modifier.fillMaxWidth(), icon = QtIcons.Download)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextAction("Later") { manager.later() }
                                TextAction("Skip this version") { manager.skip(info) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandCard(info: UpdateInfo) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFFF8FAFF))
            .border(1.dp, Color(0x1A0B1B33), shape).padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(DsR.drawable.vidgrab_wordmark),
            contentDescription = "VidGrab",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(0.62f).height(42.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandPill("NEW UPDATE", accent = true)
            BrandPill("v${info.versionName}")
            if (info.apkSize > 0) BrandPill(Format.bytes(info.apkSize))
        }
    }
}

@Composable
private fun BrandPill(text: String, accent: Boolean = false) {
    Text(
        text,
        color = Color(0xFF214B8F),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (accent) Color(0xFFDEE9FF) else Color(0xFFE9F0FF))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun VersionRoute(current: String, next: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("INSTALLED", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "v" + current.ifBlank { "current" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(QtIcons.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("AVAILABLE", style = MaterialTheme.typography.labelSmall, color = cs.primary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "v$next",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ReleaseNotes(notes: String) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(QtIcons.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            Text(
                "What's new",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
        Text(
            notes.ifBlank { "Bug fixes and performance improvements." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 170.dp)
                .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

@Composable
private fun VerifiedNote(info: UpdateInfo) {
    val verified = info.sha256Url != null
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) { Icon(QtIcons.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                if (verified) "Verified download" else "Signed by VidGrab",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (verified) "SHA-256 checksum and app signature are checked before install."
                else "Android checks the app signature before installing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private fun titleFor(state: UpdateState): String = when (state) {
    is UpdateState.NeedsPermission -> "One quick step"
    is UpdateState.Downloading -> "Downloading update"
    is UpdateState.Installing -> "Finishing up"
    is UpdateState.Failed -> "Update paused"
    else -> "Update available"
}

private fun subtitleFor(state: UpdateState): String = when (state) {
    is UpdateState.NeedsPermission -> "Allow installs once and VidGrab continues automatically."
    is UpdateState.Downloading -> "Keep using the app while the new version downloads."
    is UpdateState.Installing -> "Android is replacing VidGrab with the new version."
    is UpdateState.Failed -> "Nothing changed. Your current version is untouched."
    else -> "A newer build of VidGrab is ready to install."
}

@Composable
private fun Progress(fraction: Float, label: String) {
    Column {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(LocalQtExtras.current.brand))
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Later(manager: UpdateManager) =
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { TextAction("Later") { manager.later() } }

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
