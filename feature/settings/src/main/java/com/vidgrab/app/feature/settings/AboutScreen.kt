package com.vidgrab.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.designsystem.IconTile
import com.vidgrab.app.core.designsystem.LocalQtExtras
import com.vidgrab.app.core.designsystem.QtCard
import com.vidgrab.app.core.designsystem.QtTopBar
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.designsystem.R as DsR

private const val WEBSITE = "https://udcemon.me/VidGrab/"
private const val SOURCE = "https://github.com/emonbudc/VidGrab"
private const val ISSUES = "https://github.com/emonbudc/VidGrab/issues"
private const val LICENSE = "https://github.com/emonbudc/VidGrab/blob/main/LICENSE"

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { QtTopBar("About", onBack, subtitle = "Version " + appVersion(context)) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        ) {
            BrandCard(context)

            GroupLabel("At a glance")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile("1,800+", "supported sites", Modifier.weight(1f))
                StatTile("4K", "max quality", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile("0", "ads or accounts", Modifier.weight(1f))
                StatTile("100%", "on-device", Modifier.weight(1f))
            }

            GroupLabel("Everything you need")
            QtCard {
                FeatureRow(
                    QtIcons.Download,
                    "Download almost anything",
                    "YouTube and 1,800+ sites, up to 4K video or audio only.",
                )
                RowDivider()
                FeatureRow(
                    QtIcons.PlayCircle,
                    "Play your way",
                    "Background audio, picture-in-picture, queue, speed and touch gestures.",
                )
                RowDivider()
                FeatureRow(
                    QtIcons.Folder,
                    "Keep it organized",
                    "Downloads, playlists, vault, recycle bin and WhatsApp status saver.",
                )
                RowDivider()
                FeatureRow(
                    QtIcons.Shield,
                    "Private by design",
                    "No account, no ads. History, files and settings stay on this device.",
                )
            }

            GroupLabel("Developer")
            QtCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(17.dp))
                            .background(LocalQtExtras.current.brand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("AE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Md Abdullah Al Emon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "@emonbudc · Developer & maintainer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinkChip("GitHub", QtIcons.OpenInNew) { open(context, SOURCE) }
                    LinkChip("Website", QtIcons.Public) { open(context, WEBSITE) }
                }
            }

            GroupLabel("Project")
            QtCard {
                NavRow(QtIcons.Public, "Website", "udcemon.me/VidGrab") { open(context, WEBSITE) }
                RowDivider()
                NavRow(QtIcons.Link, "Source code", "github.com/emonbudc/VidGrab") { open(context, SOURCE) }
                RowDivider()
                NavRow(QtIcons.Feedback, "Report an issue", "Bugs, ideas and feedback") { open(context, ISSUES) }
                RowDivider()
                NavRow(QtIcons.Share, "Share VidGrab", "Send the download link to a friend") {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Download videos and music with VidGrab: $SOURCE"),
                            "Share VidGrab",
                        ),
                    )
                }
                RowDivider()
                NavRow(QtIcons.Info, "License", "GNU General Public License v3.0", trailing = "GPL-3.0") {
                    open(context, LICENSE)
                }
            }

            GroupLabel("Open source")
            QtCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "VidGrab is free software released under the GNU General Public License v3.0. " +
                            "It is built on QuickTube by devgalib.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "NewPipe Extractor (GPLv3) · AndroidX Media3 (Apache 2.0) · AndroidX Room, Navigation " +
                            "and DataStore (Apache 2.0) · Koin (Apache 2.0) · Coil (Apache 2.0) · OkHttp (Apache 2.0) · " +
                            "Kotlin Coroutines & Serialization (Apache 2.0)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            Text(
                "Only download content you own or have permission to save. Respect each site's terms of service.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            )
            Text(
                "© 2026 VidGrab · Made for Android",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BrandCard(context: Context) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(shape).background(Color(0xFFF8FAFF)).border(1.dp, Color(0x1A0B1B33), shape)
            .padding(horizontal = 22.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(DsR.drawable.vidgrab_wordmark),
                contentDescription = "VidGrab",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.72f).height(56.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandPill("v" + appVersion(context))
                BrandPill("GPL-3.0")
                BrandPill("Android 7.0+")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Download anything. Keep everything.",
                color = Color(0xFF081A33),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "A fast, private downloader for YouTube and the web. No ads, no account, no clutter.",
                color = Color(0xFF53627A),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun BrandPill(text: String) {
    Text(
        text,
        color = Color(0xFF214B8F),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFFE9F0FF))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(cs.surface)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = cs.primary)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, summary: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        IconTile(icon, size = 34.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LinkChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh)
            .pressable(scaleDown = 0.94f, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = cs.primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}
