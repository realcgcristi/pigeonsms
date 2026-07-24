package app.pigeonsms.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Dimens
import app.pigeonsms.design.theme.LocalExperimentalRedesign
import app.pigeonsms.design.theme.NovaColors
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.PigeonColors
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.novaSurface
import app.pigeonsms.ui.pigeonVm

private fun rel(ms: Long) = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) = SettingsSubHeader(title, onBack)

/** Nova gives sub-screen list items a bolder card: rounder corners + hairline outline. */
@Composable
private fun Modifier.rowCard(): Modifier {
    return if (LocalExperimentalRedesign.current) {
        // list-safe Nova depth: lifted-top gradient + lit rim, no per-row shadow
        this.fillMaxWidth().novaSurface(NovaCorners.card, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary)
    } else {
        this.fillMaxWidth().clip(Corners.group).background(MaterialTheme.colorScheme.surfaceContainer)
    }
}

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = pigeonVm { c, _ -> SettingsViewModel(c.authRepository, c.themeStore) }
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.loadSessions() }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        SubHeader("devices", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            items(ui.sessions, key = { it.id }) { s ->
                Box(Modifier.rowCard()) {
                    Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((s.device_name ?: "unknown device").lowercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                if (s.current) Text("  this device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("active ${rel(s.last_seen)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!s.current) IconButton({ vm.revoke(s.id) }) { Icon(Icons.Outlined.Close, "revoke", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = pigeonVm { c, _ -> SettingsViewModel(c.authRepository, c.themeStore) }
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.loadHistory() }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        SubHeader("login history", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            // HistoryEntry has no id — created_at plus position is stable for a loaded list
            itemsIndexed(ui.history, key = { i, h -> "${h.created_at}-$i" }) { _, h ->
                Box(Modifier.rowCard()) {
                    Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                        // Nova speaks presence in cyan; classic keeps Mint
                        val okColor = if (LocalExperimentalRedesign.current) NovaColors.Cyan else PigeonColors.Mint
                        Box(Modifier.size(10.dp).background(if (h.success == 1) okColor else PigeonColors.Danger, CircleShape))
                        Column(Modifier.padding(start = Spacing.m)) {
                            Text("${(h.device_name ?: "unknown").lowercase()} · ${if (h.success == 1) "signed in" else "failed"}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("${rel(h.created_at)}${h.ip?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = pigeonVm { c, _ -> SettingsViewModel(c.authRepository, c.themeStore) }
    val ui by vm.ui.collectAsState()
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        SubHeader("two-factor auth", onBack)
        when {
            ui.recoveryCodes.isNotEmpty() -> {
                Text("save these recovery codes somewhere safe. each works once.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.m))
                ui.recoveryCodes.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            ui.totpSetup != null -> {
                Text("add this secret to your authenticator app, then enter the 6-digit code.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.s))
                Text(ui.totpSetup!!.secret, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, Corners.input).padding(Spacing.m))
                Spacer(Modifier.height(Spacing.m))
                OutlinedTextField(code, { code = it.filter { ch -> ch.isDigit() }.take(6) }, placeholder = { Text("6-digit code") }, shape = Corners.input, modifier = Modifier.fillMaxWidth())
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(Spacing.m))
                Button({ vm.enableTotp(code) }, Modifier.fillMaxWidth(), shape = Corners.button) { Text("enable") }
            }
            else -> {
                Text("protect your account with a time-based code from an authenticator app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.l))
                Button(vm::startTotp, Modifier.fillMaxWidth(), shape = Corners.button) { Text("set up 2fa") }
            }
        }
    }
}

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    // tiny local state instead of a dedicated VM: load list, allow unblock
    val social = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.pigeonsms.PigeonApp).container.socialRepository
    var blocked by remember { mutableStateOf<List<app.pigeonsms.network.BlockedUserDto>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        blocked = runCatching { social.blocks() }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        SubHeader("blocked users", onBack)
        when {
            blocked == null -> Text("loading...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.l))
            blocked!!.isEmpty() -> Text("no blocked users", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.l))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                items(blocked!!, key = { it.id }) { u ->
                    Box(Modifier.rowCard()) {
                        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((u.display_name ?: u.username).lowercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text("@${u.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { social.unblock(u.id) }
                                    blocked = runCatching { social.blocks() }.getOrDefault(blocked ?: emptyList())
                                }
                            }, shape = Corners.button) { Text("unblock") }
                        }
                    }
                }
            }
        }
    }
}
