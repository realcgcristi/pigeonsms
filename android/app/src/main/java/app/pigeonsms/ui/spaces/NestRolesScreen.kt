package app.pigeonsms.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.settings.SettingsSubHeader
import app.pigeonsms.network.SpaceRoleDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Custom roles for a nest (2.9.5).
 *
 * Permissions are edited as **names**, never as raw bit values — the API accepts
 * either, and names mean the client never has to know or duplicate the bit
 * layout. If the server adds a permission, it can appear here without the client
 * needing to understand its bit position.
 *
 * The server refuses to let anyone grant a permission they don't themselves hold,
 * so a non-owner editing roles sees a clear `escalation` error rather than a
 * silent partial save.
 */

/** The permission names the server exposes, in the order they're shown. */
private val PERMISSION_LABELS = listOf(
    "VIEW_CHANNEL" to "see channels",
    "SEND_MESSAGES" to "send messages",
    "ATTACH_FILES" to "attach files",
    "ADD_REACTIONS" to "add reactions",
    "CREATE_THREADS" to "start threads",
    "CREATE_INVITES" to "invite people",
    "MENTION_EVERYONE" to "mention everyone",
    "MANAGE_MESSAGES" to "manage messages",
    "MANAGE_THREADS" to "manage threads",
    "MANAGE_CHANNELS" to "manage channels",
    "MANAGE_EMOJI" to "manage emoji",
    "MANAGE_ROLES" to "manage roles",
    "MANAGE_NEST" to "manage the nest",
    "KICK_MEMBERS" to "kick members",
)

data class NestRolesUiState(
    val roles: List<SpaceRoleDto> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** What the caller may do — used to explain why an edit was refused. */
    val myPermissions: List<String> = emptyList(),
    val isOwner: Boolean = false,
    val error: String? = null,
)

class NestRolesViewModel(private val repo: SocialRepository) : ViewModel() {
    private val _ui = MutableStateFlow(NestRolesUiState())
    val ui: StateFlow<NestRolesUiState> = _ui

    fun load(spaceId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val roles = repo.spaceRoles(spaceId)
                val mine = repo.spacePermissions(spaceId)
                roles to mine
            }
                .onSuccess { (roles, mine) ->
                    _ui.update {
                        it.copy(
                            roles = roles,
                            myPermissions = mine.permission_names,
                            isOwner = mine.is_owner,
                            loading = false,
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "couldn't load roles") }
                }
        }
    }

    fun create(spaceId: String, name: String, permissions: List<String>, onDone: () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching { repo.createRole(spaceId, name.trim(), permissions) }
                .onSuccess { role ->
                    _ui.update { it.copy(busy = false, roles = it.roles + role) }
                    onDone()
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, error = e.message ?: "couldn't create that role") }
                }
        }
    }

    fun update(spaceId: String, roleId: String, permissions: List<String>, onDone: () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching { repo.updateRole(spaceId, roleId, permissions = permissions) }
                .onSuccess { updated ->
                    _ui.update { state ->
                        state.copy(
                            busy = false,
                            roles = state.roles.map { if (it.id == updated.id) updated else it },
                        )
                    }
                    onDone()
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, error = e.message ?: "couldn't save that role") }
                }
        }
    }

    fun delete(spaceId: String, roleId: String) {
        viewModelScope.launch {
            runCatching { repo.deleteRole(spaceId, roleId) }
                .onSuccess {
                    _ui.update { state -> state.copy(roles = state.roles.filterNot { it.id == roleId }) }
                }
                .onFailure { e -> _ui.update { it.copy(error = e.message ?: "couldn't delete that role") } }
        }
    }
}

@Composable
fun NestRolesScreen(
    spaceId: String,
    vm: NestRolesViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var editing by remember { mutableStateOf<SpaceRoleDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftPermissions by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(spaceId) { vm.load(spaceId) }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        // Shared skin-aware header: it owns the status-bar inset and matches
        // Classic/Nova/Galaxy. The hand-rolled Row it replaces did neither, so the
        // title sat under the status bar and ignored the active skin.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) { SettingsSubHeader("roles", onBack) }
            IconButton(onClick = {
                draftName = ""
                draftPermissions = setOf("VIEW_CHANNEL", "SEND_MESSAGES")
                creating = true
            }) {
                Icon(Icons.Outlined.Add, contentDescription = "new role")
            }
        }
        Text(
            "roles stack on top of owner/admin/member — they can only grant, never take away. " +
                "use channel overrides to remove something in one channel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ui.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.roles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "no custom roles yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                Modifier.padding(top = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(ui.roles, key = { it.id }) { role ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            editing = role
                            draftName = role.name
                            draftPermissions = role.permission_names.toSet()
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(role.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${role.permission_names.size} permissions",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.delete(spaceId, role.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "delete ${role.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val target = editing
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(target?.let { "edit ${it.name}" } ?: "new role") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (target == null) {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it.take(40) },
                            label = { Text("name") },
                            singleLine = true,
                        )
                    }
                    PERMISSION_LABELS.forEach { (flag, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                draftPermissions = if (flag in draftPermissions) {
                                    draftPermissions - flag
                                } else {
                                    draftPermissions + flag
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = flag in draftPermissions,
                                onCheckedChange = { checked ->
                                    draftPermissions = if (checked) {
                                        draftPermissions + flag
                                    } else {
                                        draftPermissions - flag
                                    }
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !ui.busy && (target != null || draftName.isNotBlank()),
                    onClick = {
                        val permissions = draftPermissions.toList()
                        if (target != null) {
                            vm.update(spaceId, target.id, permissions) { editing = null }
                        } else {
                            vm.create(spaceId, draftName, permissions) { creating = false }
                        }
                    },
                ) { Text("save") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false; editing = null }) { Text("cancel") }
            },
        )
    }
}
