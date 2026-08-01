package app.pigeonsms.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.pigeonsms.PigeonApp
import app.pigeonsms.data.CapsuleCrypto
import app.pigeonsms.data.EncryptedCapsule
import app.pigeonsms.data.KeyTransparencyVerifier
import app.pigeonsms.data.LocalSession
import app.pigeonsms.data.NetworklessManager
import app.pigeonsms.data.TransparencyCheckpointStore
import app.pigeonsms.data.TimeMachineVerifier
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.SpaceDto
import app.pigeonsms.network.TimeCapsuleDto
import app.pigeonsms.network.TimeEventDto
import app.pigeonsms.network.TransparencyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Composable
fun NestTimeMachineScreen(
    spaceId: String,
    spaceName: String,
    onBack: () -> Unit,
    onImported: (String) -> Unit,
) {
    val context = LocalContext.current
    val api = (context.applicationContext as PigeonApp).container.api
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<TimeEventDto>>(emptyList()) }
    var capsules by remember { mutableStateOf<List<TimeCapsuleDto>>(emptyList()) }
    var checkpointName by remember { mutableStateOf("before the next big change") }
    var passphrase by remember { mutableStateOf("") }
    var cursor by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val history = mutableListOf<TimeEventDto>()
        var after = 0L
        for (pageNumber in 0 until 40) {
            val page = api.timeEvents(spaceId, after)
            history += page.events
            if (!page.has_more || page.cursor <= after) break
            after = page.cursor
        }
        if (!withContext(Dispatchers.Default) { TimeMachineVerifier.verify(history) }) {
            error = "history verification failed"
        }
        events = history
        capsules = api.timeCapsules(spaceId)
        cursor = events.lastIndex.coerceAtLeast(0)
    }

    LaunchedEffect(spaceId) {
        runCatching { load() }.onFailure { error = it.message ?: "could not load time machine" }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsSubHeader("nest time machine", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            item {
                Text(spaceName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("encrypted replay, restore and forks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s)) }
            }
            item {
                Group("encrypted checkpoint")
                GroupCard {
                    Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        OutlinedTextField(
                            value = checkpointName,
                            onValueChange = { checkpointName = it.take(80) },
                            label = { Text("checkpoint name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = Corners.input,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("private passphrase") },
                            supportingText = { Text("never leaves this device") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = Corners.input,
                            singleLine = true,
                        )
                        Button(
                            enabled = !busy && passphrase.length >= 8 && checkpointName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    runCatching {
                                        val exported = api.exportSpaceMigration(spaceId)
                                        val snapshot = buildJsonObject {
                                            put("bundle", exported.bundle)
                                            put("digest", exported.digest)
                                            put("captured_at", System.currentTimeMillis())
                                        }.toString()
                                        val encrypted = withContext(Dispatchers.Default) {
                                            CapsuleCrypto.encrypt(snapshot, passphrase)
                                        }
                                        api.createTimeCapsule(
                                            spaceId,
                                            checkpointName.trim(),
                                            encrypted.ciphertext,
                                            encrypted.iv,
                                            encrypted.salt,
                                            encrypted.kdf,
                                        )
                                        load()
                                    }.onFailure { error = it.message ?: "could not create checkpoint" }
                                    busy = false
                                }
                            },
                            shape = Corners.button,
                        ) { Text(if (busy) "capturing…" else "capture this nest") }
                    }
                }
            }
            item {
                Group("replay · ${events.size} events")
                GroupCard {
                    Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        if (events.isEmpty()) {
                            Text("history starts with the next change", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Slider(
                                value = cursor.toFloat(),
                                onValueChange = { cursor = it.toInt().coerceIn(0, events.lastIndex) },
                                valueRange = 0f..events.lastIndex.toFloat().coerceAtLeast(1f),
                                steps = (events.size - 2).coerceIn(0, 50),
                            )
                            val event = events[cursor.coerceIn(0, events.lastIndex)]
                            EventCard(event)
                        }
                    }
                }
            }
            item { Group("checkpoints") }
            if (capsules.isEmpty()) {
                item {
                    GroupCard { Text("no encrypted checkpoints yet", modifier = Modifier.padding(Spacing.l), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                items(capsules, key = { it.id }) { capsule ->
                    GroupCard {
                        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            Text(capsule.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "events ${capsule.event_from}–${capsule.event_to} · ${capsule.size / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                listOf("restore", "fork").forEach { mode ->
                                    OutlinedButton(
                                        enabled = !busy && passphrase.isNotBlank(),
                                        onClick = {
                                            busy = true
                                            error = null
                                            scope.launch {
                                                runCatching {
                                                    val full = api.timeCapsule(spaceId, capsule.id)
                                                    val ciphertext = full.ciphertext ?: kotlin.error("checkpoint data is missing")
                                                    if (!CapsuleCrypto.digest(ciphertext).equals(full.digest, ignoreCase = true)) {
                                                        kotlin.error("checkpoint integrity check failed")
                                                    }
                                                    val plaintext = withContext(Dispatchers.Default) {
                                                        CapsuleCrypto.decrypt(
                                                            EncryptedCapsule(ciphertext, full.iv, full.salt, full.kdf),
                                                            passphrase,
                                                        )
                                                    }
                                                    val bundle = Json.parseToJsonElement(plaintext).jsonObject["bundle"]?.jsonObject
                                                        ?: kotlin.error("checkpoint does not contain a nest")
                                                    val result = api.importSpaceMigration(
                                                        bundle,
                                                        if (mode == "fork") "$spaceName fork" else "$spaceName restored",
                                                        true,
                                                    )
                                                    onImported(result.space_id)
                                                }.onFailure { error = it.message ?: "wrong passphrase or damaged checkpoint" }
                                                busy = false
                                            }
                                        },
                                    ) { Text(mode) }
                                }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                api.deleteTimeCapsule(spaceId, capsule.id)
                                                capsules = capsules.filterNot { it.id == capsule.id }
                                            }.onFailure { error = it.message ?: "could not delete checkpoint" }
                                        }
                                    },
                                ) { Text("delete") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.huge)) }
        }
    }
}

@Composable
private fun EventCard(event: TimeEventDto) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, Corners.card).padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
            Text("#${event.sequence}  ${event.kind.replace('.', ' ')}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = Spacing.s))
        }
        Text(event.event_hash.take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(event.payload.toString(), maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun KeyTransparencyScreen(userId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = (context.applicationContext as PigeonApp).container.api
    val store = remember { TransparencyCheckpointStore(context.applicationContext) }
    var response by remember { mutableStateOf<TransparencyResponse?>(null) }
    var verified by remember { mutableStateOf<Boolean?>(null) }
    var detail by remember { mutableStateOf("checking the public device-key history") }

    LaunchedEffect(userId) {
        runCatching {
            val data = api.transparency(userId)
            val previous = store.get(userId)
            val valid = withContext(Dispatchers.Default) { KeyTransparencyVerifier.verify(data) }
            val consistent = withContext(Dispatchers.Default) { KeyTransparencyVerifier.consistent(previous, data.entries) }
            val gossip = api.gossipTransparency(userId, data.checkpoint)
            val safe = valid && consistent && !KeyTransparencyVerifier.changed(previous, data.checkpoint) && !gossip.conflict
            response = data
            verified = safe
            detail = if (safe) {
                store.put(userId, data.checkpoint)
                "the full key history is valid and consistent with this device"
            } else {
                "the key history changed unexpectedly — verify on another trusted device"
            }
        }.onFailure {
            verified = false
            detail = it.message ?: "could not verify the key history"
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsSubHeader("key transparency", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            item {
                GroupCard {
                    Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (verified == true) Icons.Outlined.CheckCircle else if (verified == false) Icons.Outlined.Warning else Icons.Outlined.Key,
                            null,
                            tint = if (verified == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = Spacing.m)) {
                            Text(if (verified == true) "key history verified" else if (verified == false) "key warning" else "verifying keys", fontWeight = FontWeight.Bold)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            response?.let { data ->
                item {
                    Group("checkpoint")
                    GroupCard {
                        Column(Modifier.padding(Spacing.l)) {
                            Text("${data.checkpoint.tree_size} recorded key events", fontWeight = FontWeight.Bold)
                            Text(data.checkpoint.root_hash, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Group("device-key history") }
                items(data.entries, key = { it.id }) { entry ->
                    GroupCard {
                        Row(Modifier.fillMaxWidth().padding(Spacing.l)) {
                            Text(entry.action, color = if (entry.action == "revoke") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Column(Modifier.padding(start = Spacing.m).weight(1f)) {
                                Text(entry.device_id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.entry_hash, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.huge)) }
        }
    }
}

@Composable
fun NetworklessScreen(
    spaces: List<SpaceDto>,
    session: LocalSession,
    manager: NetworklessManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by manager.status.collectAsState()
    var selectedId by remember(spaces) { mutableStateOf(spaces.firstOrNull()?.id.orEmpty()) }
    var passphrase by remember { mutableStateOf("") }
    val selected = spaces.firstOrNull { it.id == selectedId }

    val begin = {
        val space = selected
        if (space != null && passphrase.length >= 8) {
            scope.launch {
                runCatching {
                    manager.start(
                        space.id,
                        space.channels.filter { it.kind != "voice" }.map { it.id }.toSet(),
                        passphrase,
                        session.userId,
                        session.username,
                    )
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin()
    }

    Column(Modifier.fillMaxSize()) {
        SettingsSubHeader("networkless mode", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            item {
                GroupCard {
                    Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (status.active) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                            null,
                            tint = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = Spacing.m)) {
                            Text(if (status.active) "nearby link active" else "networkless mode is off", fontWeight = FontWeight.Bold)
                            Text(
                                if (status.active) "${status.peers} peers · ${status.exchanged} messages exchanged" else "encrypted LAN and existing Wi-Fi Direct groups",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item {
                Group("shared nest")
                GroupCard {
                    Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            items(spaces, key = { it.id }) { space ->
                                FilterChip(
                                    selected = selectedId == space.id,
                                    onClick = { if (!status.active) selectedId = space.id },
                                    label = { Text(space.name, maxLines = 1) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            enabled = !status.active,
                            label = { Text("shared encryption key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = Corners.input,
                            singleLine = true,
                        )
                        if (status.active) {
                            OutlinedButton(onClick = manager::stop, modifier = Modifier.fillMaxWidth()) { Text("stop session") }
                        } else {
                            Button(
                                enabled = selected != null && passphrase.length >= 8,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                                        permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                                    } else {
                                        begin()
                                    }
                                },
                            ) { Text("start nearby session") }
                        }
                    }
                }
            }
            item {
                GroupCard {
                    Text(
                        "messages are encrypted with the shared key, appear instantly nearby, and reconcile automatically when the sender reconnects",
                        modifier = Modifier.padding(Spacing.l),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(Spacing.huge)) }
        }
    }
}
