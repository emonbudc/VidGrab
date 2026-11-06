package com.vidgrab.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.designsystem.pressable

data class NavTab(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badges: Map<Int, Int> = emptyMap(),
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().background(scheme.surface)
            .drawBehind {
                drawLine(scheme.outlineVariant, start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, tab ->
                val active = i == selected
                val color = if (active) scheme.primary else scheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).height(62.dp)
                        .pressable(scaleDown = 0.92f, role = Role.Tab, onClick = {
                            if (!active) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        })
                        .semantics { this.selected = active },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(if (active) tab.selectedIcon else tab.icon, null, tint = color, modifier = Modifier.size(25.dp))
                        val count = badges[i] ?: 0
                        if (count > 0) Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp).size(16.dp)
                                .clip(CircleShape).background(scheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (count > 9) "9+" else "$count", color = scheme.onPrimary,
                                fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        tab.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 10.5.sp, lineHeight = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
