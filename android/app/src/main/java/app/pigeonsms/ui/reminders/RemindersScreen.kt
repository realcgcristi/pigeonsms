package app.pigeonsms.ui.reminders

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
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
import app.pigeonsms.network.ReminderDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reminders (2.9.5).
 *
 * Deliberately relative-time only ("in 1 hour", "tomorrow") rather than a
 * date-time picker. The cron that fires these runs every 5 minutes, so
 * minute-precision input would promise an accuracy the backend doesn't have — and
 * the overwhelmingly common case is "nudge me about this later", not "at exactly
 * 14:32".
 */

data class RemindersUiState(
    val reminders: List<ReminderDto> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The offsets offered in the create dialog, in milliseconds. */
private val PRESETS = listOf(
    "in 30 min" to 30 * 60 * 1000L,
    "in 1 hour" to 60 * 60 * 1000L,
    "in 3 hours" to 3 * 60 * 60 * 1000L,
    "tomorrow" to 24 * 60 * 60 * 1000L,
    "next week" to 7 * 24 * 60 * 60 * 1000L,
)

class RemindersViewModel(private val repo: SocialRepository) : ViewModel() {
    private val _ui = MutableStateFlow(RemindersUiState())
    val ui: StateFlow<RemindersUiState> = _ui

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.reminders() }
                .onSuccess { list -> _ui.update { it.copy(reminders = list, loading = false) } }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "couldn't load reminders") }
                }
        }
    }

    fun create(
        text: String,
        offsetMs: Long,
        channelId: String? = null,
        messageId: String? = null,
        onDone: () -> Unit,
    ) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching {
                repo.createReminder(text.trim(), System.currentTimeMillis() + offsetMs, channelId, messageId)
            }
                .onSuccess { created ->
                    _ui.update {
                        it.copy(
                            busy = false,
                            reminders = (it.reminders + created).sortedBy { r -> r.remind_at },
                        )
                    }
                    onDone()
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, error = e.message ?: "couldn't set that reminder") }
                }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch {
            runCatching { repo.cancelReminder(id) }
                .onSuccess {
                    _ui.update { state -> state.copy(reminders = state.reminders.filterNot { it.id == id }) }
                }
                .onFailure { e -> _ui.update { it.copy(error = e.message ?: "couldn't cancel that") } }
        }
    }
}

private fun formatWhen(epochMs: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
fun RemindersScreen(
    vm: RemindersViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var composing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(PRESETS[1].second) }

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().padding(Spacing.m)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "back")
                }
                Text("reminders", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = { text = ""; composing = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "new reminder")
            }
        }

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.reminders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "nothing to remember — tap + to add one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(ui.reminders, key = { it.id }) { reminder ->
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(reminder.text, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    formatWhen(reminder.remind_at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.cancel(reminder.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "cancel reminder",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (composing) {
        AlertDialog(
            onDismissRequest = { composing = false },
            title = { Text("remind me to…") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(500) },
                        placeholder = { Text("water the plants") },
                        maxLines = 3,
                    )
                    Column(Modifier.padding(top = Spacing.s)) {
                        PRESETS.forEach { (label, ms) ->
                            TextButton(onClick = { offset = ms }) {
                                Text(if (offset == ms) "• $label" else label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank() && !ui.busy,
                    onClick = { vm.create(text, offset) { composing = false } },
                ) { Text("set") }
            },
            dismissButton = { TextButton(onClick = { composing = false }) { Text("cancel") } },
        )
    }
}
