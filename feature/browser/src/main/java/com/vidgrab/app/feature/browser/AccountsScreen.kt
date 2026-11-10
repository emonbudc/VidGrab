package com.vidgrab.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.common.LoginSite
import com.vidgrab.app.core.common.LoginSites
import com.vidgrab.app.core.designsystem.QtCard
import com.vidgrab.app.core.designsystem.QtTopBar
import com.vidgrab.app.core.ui.BrandTile

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onLogin: (siteId: String, switchAccount: Boolean) -> Unit,
) {
    val states by SiteSessions.states.collectAsStateWithLifecycle()
    var signingOut by remember { mutableStateOf<LoginSite?>(null) }

    LaunchedEffect(Unit) { SiteSessions.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { SiteSessions.refresh() }

    Scaffold(topBar = { QtTopBar("Accounts", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
        ) {
            item {
                Text(
                    "Sign in on the site's own page. Your password never passes through VidGrab — only the " +
                        "session cookies stay on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                )
            }
            item {
                Text(
                    "Sites",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 22.dp, bottom = 8.dp),
                )
            }
            item {
                QtCard {
                    LoginSites.all.forEachIndexed { index, site ->
                        if (index > 0) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            )
                        }
                        AccountRow(
                            site = site,
                            signedIn = states[site.id] == true,
                            onSignIn = { onLogin(site.id, false) },
                            onSwitch = site.switchAccountUrl?.let { { onLogin(site.id, true) } },
                            onSignOut = { signingOut = site },
                        )
                    }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp, start = 20.dp, end = 20.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(16.dp),
                ) {
                    Text("Any other site", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Open it in the VidGrab browser and use its own sign-in link. Those cookies are kept too, " +
                            "and downloads from that site use the same session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    signingOut?.let { site ->
        AlertDialog(
            onDismissRequest = { signingOut = null },
            title = { Text("Sign out of ${site.name}?") },
            text = {
                Text(
                    "VidGrab will remove the ${site.name} account cookies from this device. Downloads that need " +
                        "an account may stop working.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SiteSessions.signOut(site)
                    signingOut = null
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signingOut = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountRow(
    site: LoginSite,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSwitch: (() -> Unit)?,
    onSignOut: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(site.name, size = 42.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(site.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (signedIn) "Signed in" else "Not signed in",
                style = MaterialTheme.typography.bodySmall,
                color = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signedIn) {
            if (onSwitch != null) {
                TextButton(onClick = onSwitch, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Switch") }
            }
            TextButton(onClick = onSignOut, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Sign out") }
        } else {
            TextButton(onClick = onSignIn, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Sign in") }
        }
    }
}
