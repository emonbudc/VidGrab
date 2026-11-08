package com.vidgrab.app.feature.search

import com.vidgrab.app.core.designsystem.icon.QtIcons
import androidx.compose.foundation.layout.size
import com.vidgrab.app.core.designsystem.IconTile
import com.vidgrab.app.core.designsystem.QtColors
import com.vidgrab.app.core.designsystem.QtIconButton
import com.vidgrab.app.core.designsystem.pressable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.Urls
import com.vidgrab.app.core.designsystem.SectionHeader
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(
    initial: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onDownloadUrl: (String) -> Unit,
) {
    val vm = koinViewModel<SearchViewModel>()
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(field.text) { vm.query.value = field.text }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (Urls.looksLikeUrl(t)) onDownloadUrl(Urls.normalize(t))
        else {
            vm.remember(t)
            onSearch(t)
        }
    }

    Scaffold(topBar = {
        Row(Modifier.statusBarsPadding().padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            QtIconButton(QtIcons.ArrowBack, "Back", onBack)
            TextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text("Search or paste link") },
                leadingIcon = { Icon(QtIcons.Search, null, Modifier.size(20.dp)) },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                trailingIcon = {
                    if (field.text.isNotEmpty()) QtIconButton(QtIcons.Close, "Clear", { field = TextFieldValue("") }, size = 36.dp, iconSize = 18.dp)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(field.text) }),
            )
            QtIconButton(QtIcons.Download, "Go", { submit(field.text) }, tint = Color(0xFF0D0F13),
                container = QtColors.LiveMint, modifier = Modifier.padding(start = 8.dp))
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            val text = field.text.trim()
            if (text.isNotEmpty()) {
                if (Urls.looksLikeUrl(text)) item {
                    SuggestionRow(QtIcons.Language, "Open ${Urls.normalize(text)}", onClick = { submit(text) })
                }
                items(suggestions) { s ->
                    SuggestionRow(QtIcons.Search, s, onClick = { submit(s) }, onFill = { field = TextFieldValue(s, TextRange(s.length)) })
                }
            } else {
                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader("Recent") { TextButton(onClick = { vm.clearHistory() }) { Text("Clear") } }
                    }
                    items(recent) { q ->
                        SuggestionRow(QtIcons.History, q, onClick = { submit(q) }, onRemove = { vm.forget(q) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    onFill: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.985f, onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, size = 36.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge)
        if (onFill != null) QtIconButton(QtIcons.NorthWest, "Fill", onFill, tint = MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 18.dp)
        if (onRemove != null) QtIconButton(QtIcons.Close, "Remove", onRemove, tint = MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 18.dp)
    }
}
