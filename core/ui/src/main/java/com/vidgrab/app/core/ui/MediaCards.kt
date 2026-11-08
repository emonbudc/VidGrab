package com.vidgrab.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vidgrab.app.core.common.Format
import com.vidgrab.app.core.common.Platform
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.designsystem.QtColors
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.icon.BrandIcons
import com.vidgrab.app.core.designsystem.icon.QtIcons
import com.vidgrab.app.core.designsystem.pressable
import com.vidgrab.app.core.model.ItemKind
import com.vidgrab.app.core.model.MediaItem

@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Int = 14,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        overlay()
    }
}

/** Small dark chip in the corner of a thumbnail (duration, LIVE, count). */
@Composable
fun BoxScope.CornerLabel(text: String, modifier: Modifier = Modifier, live: Boolean = false) {
    if (text.isBlank()) return
    Row(
        modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(7.dp))
            .background(if (live) Color(0xFFE5243B) else Color(0xCC0D0F13)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            color = Color(0xFFE7E9EE),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

/** Source stamp in the top-left of a thumbnail. */
@Composable
fun BoxScope.SourceChip(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text,
        modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(7.dp))
            .background(Color(0xCC0D0F13)).padding(horizontal = 5.dp, vertical = 2.dp),
        color = QtColors.LiveMint,
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        letterSpacing = 0.5.sp,
    )
}

@Composable
fun Avatar(url: String?, size: Int = 36, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) Icon(QtIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size * 0.55f).dp))
        else AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

fun MediaItem.subtitle(): String = when (kind) {
    ItemKind.CHANNEL -> listOf(
        if (subscriberCount >= 0) "${Format.count(subscriberCount)} subscribers" else "",
        if (streamCount >= 0) "$streamCount videos" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    ItemKind.PLAYLIST -> listOf(uploader, if (streamCount >= 0) "$streamCount videos" else "").filter { it.isNotBlank() }.joinToString(" · ")
    ItemKind.VIDEO -> listOf(uploader, Format.views(viewCount), uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Squared download button that keeps a 48dp touch target around a compact glyph. */
@Composable
fun DownloadIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).pressable(scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QtIcons.Download, "Download", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Full-width feed card: large thumbnail, avatar, title and a quick download button. */
@Composable
fun VideoFeedCard(item: MediaItem, onClick: () -> Unit, onDownload: () -> Unit, onChannel: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().pressable(scaleDown = 0.995f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 20) {
            FeedSourceBadge(item.url)
            FeedDurationBadge(
                text = when {
                    item.isLive -> "LIVE"
                    item.durationSec > 0 -> Format.duration(item.durationSec)
                    else -> ""
                },
                live = item.isLive,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                item.uploaderAvatar,
                38,
                Modifier.then(if (onChannel != null) Modifier.pressable(scaleDown = 0.9f, onClick = onChannel) else Modifier),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    item.title,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = cs.onSurface,
                )
                if (item.uploader.isNotBlank()) Text(
                    item.uploader,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                val meta = listOf(Format.views(item.viewCount), item.uploadDate.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) Text(
                    meta,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            FeedDownloadButton(onDownload)
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.7f))
    }
}

@Composable
private fun FeedSourceBadge(url: String) {
    val source = feedSource(url)
    Box(
        Modifier.padding(10.dp).height(24.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xB312141A)).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (source.icon != null) {
                Icon(source.icon, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                source.label,
                color = Color.White,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun BoxScope.FeedDurationBadge(text: String, live: Boolean) {
    if (text.isBlank()) return
    Box(
        Modifier.align(Alignment.BottomEnd).padding(10.dp).height(24.dp).clip(RoundedCornerShape(8.dp))
            .background(if (live) Color(0xF2E5243B) else Color(0xB30A0C10)).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                color = Color.White,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

@Composable
private fun FeedDownloadButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).pressable(scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QtIcons.Download, "Download", tint = cs.primary, modifier = Modifier.size(20.dp))
        }
    }
}

private data class FeedSource(val label: String, val icon: ImageVector?)

private fun feedSource(url: String): FeedSource = when (Urls.platformOf(url)) {
    Platform.YOUTUBE -> FeedSource("YT", BrandIcons.YouTube)
    Platform.INSTAGRAM -> FeedSource("IG", BrandIcons.Instagram)
    Platform.FACEBOOK -> FeedSource("FB", BrandIcons.Facebook)
    Platform.TIKTOK -> FeedSource("TT", BrandIcons.TikTok)
    Platform.TWITTER -> FeedSource("X", BrandIcons.X)
    Platform.WHATSAPP -> FeedSource("WA", BrandIcons.WhatsApp)
    Platform.SOUNDCLOUD -> FeedSource("SC", BrandIcons.SoundCloud)
    Platform.OTHER -> FeedSource("WEB", null)
}

/** Compact list row: thumbnail on the left. Used for search results and related videos. */
@Composable
fun MediaRow(
    item: MediaItem,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.kind == ItemKind.CHANNEL) {
            Box(Modifier.width(132.dp), contentAlignment = Alignment.Center) { Avatar(item.thumbnail, 70) }
        } else {
            Thumbnail(item.thumbnail, Modifier.width(132.dp).aspectRatio(16f / 9f), corner = 14) {
                SourceChip(platformTag(item.url))
                if (item.kind == ItemKind.PLAYLIST) {
                    CornerLabel(if (item.streamCount >= 0) "${item.streamCount} videos" else "PLAYLIST")
                } else {
                    CornerLabel(if (item.isLive) "LIVE" else Format.duration(item.durationSec), live = item.isLive)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (onDownload != null && item.kind == ItemKind.VIDEO && !item.isLive) DownloadIconButton(onDownload)
        else if (onMore != null) QtIconButton(QtIcons.MoreVert, "More", onMore, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Square card used in horizontal music rows. */
@Composable
fun MediaTile(item: MediaItem, onClick: () -> Unit, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(160.dp).pressable(scaleDown = 0.97f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14) {
            if (item.kind == ItemKind.PLAYLIST) CornerLabel(if (item.streamCount >= 0) "${item.streamCount} videos" else "PLAYLIST")
            else CornerLabel(Format.duration(item.durationSec))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC0D0F13))
                    .pressable(scaleDown = 0.86f, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Download, "Download", tint = QtColors.LiveMint, modifier = Modifier.size(17.dp)) }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            item.subtitle(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

private fun platformTag(url: String): String = when (Urls.platformOf(url)) {
    Platform.YOUTUBE -> "YT"
    Platform.INSTAGRAM -> "IG"
    Platform.FACEBOOK -> "FB"
    Platform.TIKTOK -> "TT"
    Platform.TWITTER -> "X"
    Platform.WHATSAPP -> "WA"
    Platform.SOUNDCLOUD -> "SC"
    Platform.OTHER -> "WEB"
}
