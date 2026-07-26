package app.pigeonsms.ui.spaces

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.data.displayNameFor
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.network.SpaceMemberDto
import app.pigeonsms.network.SpaceRoleDto
import app.pigeonsms.ui.settings.SettingsSubHeader
import app.pigeonsms.ui.util.Avatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A nest's member list (2.9.5).
 *
 * Visible to **every** member, not just admins — knowing who else is in a room is
 * table stakes, and the roster endpoint has always been membership-gated anyway.
 * Role management stays in the roles screen; this is read-only.
 *
 * Names respect your private nicknames, so someone you've renamed reads the same
 * here as everywhere else.
 */

data class NestMembersUiState(
    val members: List<SpaceMemberDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The nest's custom roles, for the assign sheet (2.9.6). */
    val roles: List<SpaceRoleDto> = emptyList(),
    /** True when the viewer may assign roles — drives whether the + shows. */
    val canManageRoles: Boolean = false,
    val busy: Boolean = false,
)

class NestMembersViewModel(private val repo: SocialRepository) : ViewModel() {
    private val _ui = MutableStateFlow(NestMembersUiState())
    val ui: StateFlow<NestMembersUiState> = _ui

    fun mediaUrl(key: String?): String? = repo.mediaUrl(key)

    fun load(spaceId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            // Roles + our own permissions ride along: the assign button only makes
            // sense if there are custom roles AND the viewer may hand them out.
            runCatching {
                val roles = runCatching { repo.spaceRoles(spaceId) }.getOrDefault(emptyList())
                val mine = runCatching { repo.spacePermissions(spaceId) }.getOrNull()
                _ui.update {
                    it.copy(
                        roles = roles,
                        canManageRoles = mine?.is_owner == true ||
                            mine?.permission_names?.contains("MANAGE_ROLES") == true,
                    )
                }
            }
            runCatching { repo.spaceMembers(spaceId) }
                .onSuccess { list ->
                    // Owner first, then admins, then everyone alphabetically — the
                    // order people actually scan for.
                    val ordered = list.sortedWith(
                        compareBy({ rank(it.role) }, { it.username.lowercase() }),
                    )
                    _ui.update { it.copy(members = ordered, loading = false) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "couldn't load members") }
                }
        }
    }

    /** Replace one member's custom roles (2.9.6). */
    fun assignRoles(spaceId: String, userId: String, roleIds: List<String>, onDone: () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching { repo.setMemberRoles(spaceId, userId, roleIds) }
                .onSuccess {
                    _ui.update { it.copy(busy = false) }
                    onDone()
                }
                .onFailure { e ->
                    // The server refuses to let you grant a permission you don't
                    // hold, so surface its message rather than a generic failure.
                    _ui.update { it.copy(busy = false, error = e.message ?: "couldn't save those roles") }
                }
        }
    }

    private fun rank(role: String): Int = when (role) {
        "owner" -> 0
        "admin" -> 1
        else -> 2
    }
}

@Composable
fun NestMembersScreen(
    spaceId: String,
    vm: NestMembersViewModel,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val nicknameStore = remember(context) {
        (context.applicationContext as? app.pigeonsms.PigeonApp)?.container?.nicknameStore
    }
    val nicknames by (nicknameStore?.nicknames ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())
    var assignTarget by remember { mutableStateOf<SpaceMemberDto?>(null) }
    var draftRoles by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(spaceId) { vm.load(spaceId) }

    assignTarget?.let { member ->
        AssignRolesDialog(
            member = member,
            roles = ui.roles,
            selected = draftRoles,
            busy = ui.busy,
            onToggle = { id -> draftRoles = if (id in draftRoles) draftRoles - id else draftRoles + id },
            onSave = {
                vm.assignRoles(spaceId, member.id, draftRoles.toList()) { assignTarget = null }
            },
            onDismiss = { assignTarget = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        SettingsSubHeader("members", onBack)

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.members.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "nobody here yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(ui.members, key = { it.id }) { member ->
                    val shown = nicknames.displayNameFor(
                        member.id,
                        member.display_name ?: member.username,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenProfile(member.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.s),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(shown, vm.mediaUrl(member.avatar_key), 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(shown, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "@${member.username}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (member.role != "member") {
                                Text(
                                    member.role,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            // 2.9.6: assign custom roles without leaving the list.
                            // Only shown when the nest has roles AND you may grant
                            // them — the server rejects it otherwise anyway.
                            if (ui.canManageRoles && ui.roles.isNotEmpty()) {
                                IconButton(onClick = {
                                    draftRoles = emptySet()
                                    assignTarget = member
                                }) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = "give ${member.username} a role",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignRolesDialog(
    member: SpaceMemberDto,
    roles: List<SpaceRoleDto>,
    selected: Set<String>,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("roles for ${member.username}") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "this replaces their custom roles — it doesn't touch owner/admin/member.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                roles.forEach { role ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(role.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = role.id in selected, onCheckedChange = { onToggle(role.id) })
                        Text(role.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onSave) { Text("save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}
