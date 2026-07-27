package app.pigeonsms.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.BotCommandDto
import app.pigeonsms.network.BotDto
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.ui.pigeonVm
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch

/**
 * Bot manager (v3).
 *
 * Deliberately flat: everything a bot owner needs lives on one screen, because
 * a bot has a short life cycle — create it, copy the token once, drop it into a
 * nest, and occasionally rotate or delete it. Command *authoring* stays in the
 * SDK/API where a deploy script belongs; this only shows what is registered.
 */
/** Carries the API into composition through the standard VM factory. */
class BotsApiHolder(val api: PigeonApi) : ViewModel()

@Composable
fun BotsScreen(onBack: () -> Unit) {
    // No VM of its own: this screen is a thin shell over the bot endpoints, and
    // a ViewModel would only forward calls. The container is reached through the
    // same factory every other screen uses.
    val holder: BotsApiHolder = pigeonVm { c, _ -> BotsApiHolder(c.api) }
    val api: PigeonApi = holder.api
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var bots by remember { mutableStateOf<List<BotDto>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<SpaceDto>>(emptyList()) }
    var commands by remember { mutableStateOf<Map<String, List<BotCommandDto>>>(emptyMap()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var freshToken by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<BotDto?>(null) }
    var joinTarget by remember { mutableStateOf<BotDto?>(null) }

    suspend fun reload() {
        runCatching { api.bots() }.onSuccess { bots = it }.onFailure { error = it.message }
    }

    LaunchedEffect(Unit) {
        reload()
        runCatching { api.spaces() }.onSuccess { spaces = it }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
        SettingsSubHeader("bots", onBack)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            item {
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Text("  new bot")
                }
            }

            if (bots.isEmpty()) {
                item {
                    Text(
                        "no bots yet. a bot gets its own account, joins your nests and answers slash commands.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.l),
                    )
                }
            }

            items(bots, key = { it.id }) { bot ->
                val open = expanded == bot.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(Corners.card)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            expanded = if (open) null else bot.id
                            if (!open && commands[bot.id] == null) {
                                scope.launch {
                                    runCatching { api.botCommands(bot.id) }
                                        .onSuccess { commands = commands + (bot.id to it) }
                                }
                            }
                        }
                        .padding(Spacing.l),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                bot.name.lowercase(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "@${bot.username ?: bot.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                busy = true
                                runCatching { api.rotateBotToken(bot.id) }
                                    .onSuccess { freshToken = it.token }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        }) {
                            Icon(Icons.Outlined.Refresh, "rotate token", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { pendingDelete = bot }) {
                            Icon(Icons.Outlined.Delete, "delete bot", tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (open) {
                        if (!bot.description.isNullOrBlank()) {
                            Text(
                                bot.description!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.s),
                            )
                        }
                        Text(
                            if (bot.interactions_url.isNullOrBlank()) {
                                "long-poll mode — the bot pulls its interactions"
                            } else {
                                "webhook: ${bot.interactions_url}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )

                        val list = commands[bot.id].orEmpty()
                        Text(
                            if (list.isEmpty()) "no commands registered" else "commands",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.m),
                        )
                        list.forEach { command ->
                            Text(
                                "/${command.name} — ${command.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = Spacing.xxs),
                            )
                        }

                        Button(
                            onClick = { joinTarget = bot },
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                        ) {
                            Text("add to a nest")
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = Spacing.s),
                    )
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("new bot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(32) },
                        label = { Text("name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it.take(140) },
                        label = { Text("what it does") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { api.createBot(newName.trim(), newDescription.trim()) }
                                .onSuccess {
                                    freshToken = it.token
                                    newName = ""
                                    newDescription = ""
                                    showCreate = false
                                    reload()
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { Text("create") }
            },
            dismissButton = { TextButton({ showCreate = false }) { Text("cancel") } },
        )
    }

    freshToken?.let { token ->
        AlertDialog(
            onDismissRequest = { freshToken = null },
            title = { Text("copy this token now") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        "this is the only time it is shown. anyone holding it is the bot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(Corners.chip)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(Spacing.m),
                    ) {
                        Text(token, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(token))
                    freshToken = null
                }) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Text("  copy")
                }
            },
            dismissButton = { TextButton({ freshToken = null }) { Text("done") } },
        )
    }

    pendingDelete?.let { bot ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("delete ${bot.name}?") },
            text = { Text("it leaves every nest and its token stops working. messages it sent stay.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.deleteBot(bot.id) }.onFailure { error = it.message }
                        pendingDelete = null
                        reload()
                    }
                }) { Text("delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("cancel") } },
        )
    }

    joinTarget?.let { bot ->
        AlertDialog(
            onDismissRequest = { joinTarget = null },
            title = { Text("add ${bot.name} to") },
            text = {
                Column {
                    val manageable = spaces.filter { it.role == "owner" || it.role == "admin" }
                    if (manageable.isEmpty()) {
                        Text("you don't manage any nests yet.")
                    }
                    manageable.forEach { space ->
                        Text(
                            space.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        runCatching { api.botJoinSpace(bot.id, space.id) }
                                            .onFailure { error = it.message }
                                        joinTarget = null
                                    }
                                }
                                .padding(vertical = Spacing.s),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton({ joinTarget = null }) { Text("close") } },
        )
    }
}
