package app.pigeonsms.ui.threads

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
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
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.ui.settings.SettingsSubHeader
import app.pigeonsms.network.MessageDto
import app.pigeonsms.network.PigeonApi
import app.pigeonsms.network.ThreadDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Threads (2.9.5).
 *
 * Two screens: the list of threads in a channel, and one thread's replies.
 *
 * Replies are ordinary messages carrying `thread_id`, so this screen deliberately
 * does *not* reimplement the composer, outbox or optimistic-send machinery from
 * ChatScreen. A thread reply posts straight through the API and appends the
 * server's row — simpler and correct, at the cost of no offline queueing inside a
 * thread. Threads are a side conversation; the main channel keeps the full
 * machinery.
 */

data class ThreadsUiState(
    val threads: List<ThreadDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class ThreadsViewModel(private val api: PigeonApi) : ViewModel() {
    private val _ui = MutableStateFlow(ThreadsUiState())
    val ui: StateFlow<ThreadsUiState> = _ui

    fun load(channelId: String, archived: Boolean = false) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { api.channelThreads(channelId, archived) }
                .onSuccess { list -> _ui.update { it.copy(threads = list, loading = false) } }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "couldn't load threads") }
                }
        }
    }
}

data class ThreadUiState(
    val thread: ThreadDto? = null,
    val root: MessageDto? = null,
    val messages: List<MessageDto> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
)

class ThreadViewModel(private val api: PigeonApi) : ViewModel() {
    private val _ui = MutableStateFlow(ThreadUiState())
    val ui: StateFlow<ThreadUiState> = _ui

    fun load(threadId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val detail = api.thread(threadId)
                val page = api.threadMessages(threadId)
                detail to page
            }
                .onSuccess { (detail, page) ->
                    _ui.update {
                        it.copy(
                            thread = detail.thread,
                            root = detail.root,
                            messages = page.messages,
                            loading = false,
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "couldn't open that thread") }
                }
        }
    }

    fun send(threadId: String, text: String) {
        val content = text.trim()
        if (content.isEmpty() || _ui.value.sending) return
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            runCatching { api.sendThreadMessage(threadId, content) }
                .onSuccess { message ->
                    // Append locally rather than refetching: the reply we got back
                    // is already the authoritative server row.
                    _ui.update { it.copy(sending = false, messages = it.messages + message) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(sending = false, error = e.message ?: "couldn't send that") }
                }
        }
    }

    fun setArchived(threadId: String, archived: Boolean) {
        viewModelScope.launch {
            runCatching { api.updateThread(threadId, archived = archived) }
                .onSuccess { updated -> _ui.update { it.copy(thread = updated) } }
                .onFailure { e -> _ui.update { it.copy(error = e.message ?: "couldn't update that thread") } }
        }
    }
}

@Composable
fun ThreadsScreen(
    channelId: String,
    vm: ThreadsViewModel,
    onBack: () -> Unit,
    onOpenThread: (String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(channelId) { vm.load(channelId) }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        // Shared skin-aware header — owns the status-bar inset and the skin styling.
        SettingsSubHeader("threads", onBack)

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.threads.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "no threads here yet — long-press a message to start one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(ui.threads, key = { it.id }) { thread ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenThread(thread.id) },
                    ) {
                        Column(Modifier.padding(Spacing.s)) {
                            Text(thread.title ?: "thread", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${thread.reply_count} " + if (thread.reply_count == 1) "reply" else "replies",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThreadScreen(
    threadId: String,
    vm: ThreadViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(threadId) { vm.load(threadId) }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.m)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) {
                SettingsSubHeader(ui.thread?.title ?: "thread", onBack)
            }
            ui.thread?.let { thread ->
                TextButton(onClick = { vm.setArchived(threadId, !thread.archived) }) {
                    Text(if (thread.archived) "unarchive" else "archive")
                }
            }
        }

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                ui.root?.let { root ->
                    item(key = "root") {
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                            Column(Modifier.fillMaxWidth().padding(Spacing.s)) {
                                Text(
                                    root.author.username,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(root.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                items(ui.messages, key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
                        Text(
                            message.author.username,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Archived threads are read-only server-side; hiding the composer says
            // so before the user types a reply that would be rejected.
            if (ui.thread?.archived != true) {
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("reply…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 6,
                    )
                    FilledIconButton(
                        onClick = { vm.send(threadId, draft); draft = "" },
                        enabled = draft.isNotBlank() && !ui.sending,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "send reply")
                    }
                }
            }
        }
    }
}
