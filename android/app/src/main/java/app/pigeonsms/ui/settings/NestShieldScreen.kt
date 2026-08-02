package app.pigeonsms.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pigeonsms.PigeonApp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.MemberTimeoutDto
import app.pigeonsms.network.ModerationReportDto
import app.pigeonsms.network.NestShieldResponse
import app.pigeonsms.network.NestShieldSettingsDto
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.ShieldActionDto
import app.pigeonsms.network.SpaceMemberDto
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun NestShieldScreen(spaceId: String, spaceName: String, onBack: () -> Unit) {
    val api = (LocalContext.current.applicationContext as PigeonApp).container.api
    val scope = rememberCoroutineScope()
    var shield by remember { mutableStateOf<NestShieldResponse?>(null) }
    var members by remember { mutableStateOf<List<SpaceMemberDto>>(emptyList()) }
    var reports by remember { mutableStateOf<List<ModerationReportDto>>(emptyList()) }
    var timeouts by remember { mutableStateOf<List<MemberTimeoutDto>>(emptyList()) }
    var actions by remember { mutableStateOf<List<ShieldActionDto>>(emptyList()) }
    var terms by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(600) }
    var timeoutReason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val channelSeconds = remember { mutableStateMapOf<String, String>() }

    suspend fun load() {
        val next = api.nestShield(spaceId)
        shield = next
        terms = next.settings.blocked_terms.joinToString("\n")
        next.channels.forEach { channelSeconds[it.channel_id] = it.slowmode_seconds.toString() }
        members = api.spaceMembers(spaceId)
        reports = runCatching { api.moderationReports(spaceId) }.getOrDefault(emptyList())
        timeouts = runCatching { api.memberTimeouts(spaceId) }.getOrDefault(emptyList())
        actions = runCatching { api.shieldActions(spaceId) }.getOrDefault(emptyList())
    }

    LaunchedEffect(spaceId) {
        runCatching { load() }.onFailure { error = it.message ?: "could not load Nest Shield" }
    }

    fun update(block: (NestShieldSettingsDto) -> NestShieldSettingsDto) {
        shield = shield?.let { it.copy(settings = block(it.settings)) }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsSubHeader("Nest Shield", onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            item {
                GroupCard {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (shield?.settings?.lockdown == true) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer,
                                Corners.card,
                            )
                            .padding(Spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (shield?.settings?.lockdown == true) Icons.Outlined.GppBad else Icons.Outlined.Security,
                            null,
                            tint = if (shield?.settings?.lockdown == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp),
                        )
                        Column(Modifier.padding(start = Spacing.m)) {
                            Text(
                                when {
                                    shield?.settings?.lockdown == true -> "emergency lockdown active"
                                    shield?.settings?.enabled == true -> "$spaceName is protected"
                                    else -> "Nest Shield is off"
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "raid protection, automod, reports, timeouts and slow mode",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.s)) }
            }

            shield?.let { current ->
                item { Group("protection") }
                item {
                    GroupCard {
                        ShieldToggle("Nest Shield", "enforce protection rules", current.settings.enabled) { update { it.copy(enabled = it.enabled.not()) } }
                        ShieldToggle("emergency lockdown", "pause joins and member messages", current.settings.lockdown) { update { it.copy(lockdown = it.lockdown.not()) } }
                        ShieldToggle("anti-raid", "lock on abnormal join velocity", current.settings.anti_raid) { update { it.copy(anti_raid = it.anti_raid.not()) } }
                        ShieldToggle("automod", "scan nest messages before delivery", current.settings.automod_enabled) { update { it.copy(automod_enabled = it.automod_enabled.not()) } }
                        ShieldToggle("block spam", "stop floods and duplicates", current.settings.block_spam) { update { it.copy(block_spam = it.block_spam.not()) } }
                        ShieldToggle("block external invites", "Discord, Slack, Matrix and Telegram", current.settings.block_external_invites) { update { it.copy(block_external_invites = it.block_external_invites.not()) } }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        ShieldNumberField(
                            "join limit",
                            current.settings.raid_join_limit,
                            Modifier.weight(1f),
                        ) { value -> update { it.copy(raid_join_limit = value.coerceIn(3, 500)) } }
                        ShieldNumberField(
                            "mention limit",
                            current.settings.mention_limit,
                            Modifier.weight(1f),
                        ) { value -> update { it.copy(mention_limit = value.coerceIn(1, 100)) } }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        ShieldNumberField(
                            "raid window sec",
                            current.settings.raid_window_seconds,
                            Modifier.weight(1f),
                        ) { value -> update { it.copy(raid_window_seconds = value.coerceIn(10, 3600)) } }
                        ShieldNumberField(
                            "default slow sec",
                            current.settings.default_slowmode_seconds,
                            Modifier.weight(1f),
                        ) { value -> update { it.copy(default_slowmode_seconds = value.coerceIn(0, 21_600)) } }
                    }
                }
                item {
                    OutlinedTextField(
                        value = terms,
                        onValueChange = { terms = it },
                        label = { Text("blocked terms") },
                        supportingText = { Text("one term per line") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = Corners.input,
                    )
                }
                item {
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    val next = current.settings.copy(
                                        blocked_terms = terms.lines().map(String::trim).filter(String::isNotEmpty).distinct(),
                                    )
                                    shield = current.copy(settings = api.updateNestShield(spaceId, next))
                                    load()
                                }.onFailure { error = it.message ?: "could not save Nest Shield" }
                                busy = false
                            }
                        },
                        shape = Corners.button,
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("save protection rules")
                    }
                }

                if (current.channels.isNotEmpty()) {
                    item { Group("channel slow mode") }
                    items(current.channels, key = { it.channel_id }) { channel ->
                        GroupCard {
                            Row(
                                Modifier.fillMaxWidth().padding(Spacing.m),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                            ) {
                                Text("#${channel.name ?: "channel"}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                OutlinedTextField(
                                    value = channelSeconds[channel.channel_id] ?: "0",
                                    onValueChange = { channelSeconds[channel.channel_id] = it.filter(Char::isDigit).take(5) },
                                    label = { Text("seconds") },
                                    singleLine = true,
                                    modifier = Modifier.weight(.7f),
                                )
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            api.updateChannelShield(spaceId, channel.channel_id, channelSeconds[channel.channel_id]?.toIntOrNull() ?: 0)
                                        }.onFailure { error = it.message ?: "could not update slow mode" }
                                    }
                                }) { Text("set") }
                            }
                        }
                    }
                }
            }

            item { Group("time out a member") }
            item {
                GroupCard {
                    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            items(members.filter { it.role != "owner" && timeouts.none { timeout -> timeout.user_id == it.id } }, key = { it.id }) { member ->
                                FilterChip(
                                    selected = target == member.id,
                                    onClick = { target = member.id },
                                    label = { Text(member.display_name ?: member.username) },
                                )
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            items(listOf(600 to "10 min", 3600 to "1 hour", 86400 to "1 day", 604800 to "1 week")) { option ->
                                FilterChip(selected = duration == option.first, onClick = { duration = option.first }, label = { Text(option.second) })
                            }
                        }
                        OutlinedTextField(
                            value = timeoutReason,
                            onValueChange = { timeoutReason = it.take(500) },
                            label = { Text("reason") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = target.isNotBlank() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { api.timeoutMember(spaceId, target, duration, timeoutReason) }
                                        .onSuccess { load(); target = ""; timeoutReason = "" }
                                        .onFailure { error = it.message ?: "could not time out member" }
                                    busy = false
                                }
                            },
                        ) { Text("time out") }
                    }
                }
            }
            items(timeouts, key = { it.user_id }) { timeout ->
                GroupCard {
                    Row(Modifier.fillMaxWidth().padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                            Text(timeout.display_name ?: timeout.username, color = MaterialTheme.colorScheme.onSurface)
                            Text(timeout.reason ?: "no reason", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                api.clearMemberTimeout(spaceId, timeout.user_id)
                                timeouts = timeouts.filterNot { it.user_id == timeout.user_id }
                            }
                        }) { Text("clear") }
                    }
                }
            }

            item { Group("open reports · ${reports.size}") }
            if (reports.isEmpty()) {
                item { GroupCard { Text("no open reports", modifier = Modifier.padding(Spacing.l), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } else {
                items(reports, key = { it.id }) { report ->
                    ReportCard(report, onResolve = { status ->
                        scope.launch {
                            runCatching { api.resolveModerationReport(spaceId, report.id, status) }
                                .onSuccess { reports = reports.filterNot { it.id == report.id } }
                                .onFailure { error = it.message ?: "could not update report" }
                        }
                    })
                }
            }

            item { Group("recent Shield activity") }
            if (actions.isEmpty()) {
                item { GroupCard { Text("no Shield activity yet", modifier = Modifier.padding(Spacing.l), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } else {
                items(actions.take(30), key = { it.id }) { action ->
                    GroupCard {
                        Row(Modifier.fillMaxWidth().padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = Spacing.m)) {
                                Text(action.kind.replace('_', ' '), color = MaterialTheme.colorScheme.onSurface)
                                Text(action.detail ?: action.display_name ?: action.username ?: "Nest Shield", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
private fun ShieldToggle(title: String, detail: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun ShieldNumberField(label: String, value: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { input -> input.filter(Char::isDigit).toIntOrNull()?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        shape = Corners.input,
    )
}

@Composable
private fun ReportCard(report: ModerationReportDto, onResolve: (String) -> Unit) {
    val content = runCatching {
        report.evidence?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty().ifBlank { "attachment or empty message" }
    GroupCard {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Report, null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "${report.category} · ${report.reported_username ?: report.reported_user_id}",
                    modifier = Modifier.padding(start = Spacing.s),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(content, color = MaterialTheme.colorScheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
            report.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Text(report.evidence_hash.take(24), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = { onResolve("resolved") }) { Text("resolve") }
                OutlinedButton(onClick = { onResolve("dismissed") }) { Text("dismiss") }
            }
        }
    }
}
