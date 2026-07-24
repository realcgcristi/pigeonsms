package app.pigeonsms.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pigeonsms.design.components.NovaSectionLabel
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.LocalUiSkin
import app.pigeonsms.design.theme.NovaCorners
import app.pigeonsms.design.theme.Spacing
import app.pigeonsms.design.theme.UiSkin
import app.pigeonsms.network.MessageDto
import app.pigeonsms.ui.itemAppear
import app.pigeonsms.ui.pigeonVm
import app.pigeonsms.ui.util.Empty
import app.pigeonsms.ui.util.ErrorState
import app.pigeonsms.ui.util.LoadingState
import app.pigeonsms.ui.util.clickableScale
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Nest-wide message search. A search bar drives [PigeonApi.searchSpace] (FTS5,
 * server-side, encrypted messages skipped), and results list a message preview
 * plus the channel it lives in. Tapping a result opens that channel; tapping an
 * author avatar opens their profile. Wired into [AppShell] under route "search"
 * by A2.
 *
 * Because the "search" route is global (no space arg), the screen fetches the
 * caller's nests itself and lets them pick which nest to search — defaulting to
 * the first one. Styled for all three skins (classic / nova / galaxy) via
 * [LocalUiSkin].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenChat: (id: String, name: String) -> Unit,
    onOpenProfile: (id: String) -> Unit,
) {
    val vm: SearchViewModel = pigeonVm { c, _ -> SearchViewModel(c.socialRepository, c.api) }
    val ui by vm.ui.collectAsState()
    val skin = LocalUiSkin.current
    val expressive = skin == UiSkin.Galaxy || skin == UiSkin.Nova

    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadSpaces() }
    // Debounced search — re-run whenever the query or the selected nest changes.
    LaunchedEffect(query, ui.selectedSpaceId) {
        val q = query.trim()
        if (q.length < 2 || ui.selectedSpaceId == null) {
            vm.clearResults()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(280)
        vm.search(q)
    }

    Column(Modifier.fillMaxSize()) {
        // Header: back + search field.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Spacing.xs, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(64) },
                placeholder = { Text("search messages") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, "clear", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = if (expressive) NovaCorners.input else Corners.input,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query.trim()) }),
            )
        }

        // Nest picker — only shown when there's more than one nest to disambiguate.
        if (ui.spaces.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(ui.spaces, key = { it.id }) { space ->
                    val selected = space.id == ui.selectedSpaceId
                    NestFilterChip(
                        label = space.name,
                        selected = selected,
                        onClick = { vm.selectSpace(space.id) },
                    )
                }
            }
        }

        val trimmed = query.trim()

        when {
            ui.error != null -> ErrorState(ui.error!!) { vm.search(trimmed) }
            ui.loadingSpaces && ui.spaces.isEmpty() -> LoadingState("loading nests")
            ui.selectedSpaceId == null ->
                Empty("no nests yet", "join or create a bird nest to search its messages", icon = Icons.Outlined.Forum)
            trimmed.length < 2 ->
                Empty("search a nest", "type at least two characters to search messages", icon = Icons.Outlined.Search)
            ui.searching && ui.results.isEmpty() -> LoadingState("searching")
            ui.results.isEmpty() ->
                Empty("no matches", "nothing in this nest matches “$trimmed”", icon = Icons.Outlined.Search)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (expressive) {
                    item { NovaSectionLabel("results", accent = true, modifier = Modifier.padding(vertical = Spacing.xs)) }
                }
                itemsIndexed(ui.results, key = { _, m -> m.id }) { index, message ->
                    SearchResultRow(
                        message = message,
                        channelName = ui.channelName(message.channel_id),
                        avatarUrl = message.author.avatar_key?.let { vm.mediaUrl(it) },
                        expressive = expressive,
                        modifier = Modifier.itemAppear(index),
                        onClick = { onOpenChat(message.channel_id, ui.channelName(message.channel_id)) },
                        onAvatarClick = { onOpenProfile(message.author.id) },
                    )
                }
            }
        }
    }
}

/** A selectable nest filter pill in the search header. */
@Composable
private fun NestFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickableScale(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A single search hit: author avatar + name, message preview, and the channel it
 * lives in. Expressive skins get a filled card; classic keeps a calm tinted
 * surface. Tap opens the channel; tapping the avatar opens the author's profile.
 */
@Composable
private fun SearchResultRow(
    message: MessageDto,
    channelName: String,
    avatarUrl: String?,
    expressive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val shape = if (expressive) NovaCorners.card else Corners.card
    val authorName = message.author.display_name?.takeIf { it.isNotBlank() } ?: message.author.username
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickableScale(onClick = onClick)
            .padding(Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickableScale(onClick = onAvatarClick),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = authorName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    authorName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tag, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        channelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.xxs),
                    )
                }
            }
            Text(
                preview(message.content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xxs),
                letterSpacing = 0.sp,
            )
        }
    }
}

/** Collapse whitespace and fall back to a placeholder for empty/attachment-only hits. */
private fun preview(content: String): String {
    val trimmed = content.replace(Regex("\\s+"), " ").trim()
    return if (trimmed.isEmpty()) "(no text)" else trimmed
}

/** A nest the caller can search, reduced to what the picker + channel lookup need. */
data class SearchSpace(
    val id: String,
    val name: String,
    val channelNames: Map<String, String>,
)

data class SearchUiState(
    val spaces: List<SearchSpace> = emptyList(),
    val selectedSpaceId: String? = null,
    val loadingSpaces: Boolean = false,
    val searching: Boolean = false,
    val results: List<MessageDto> = emptyList(),
    val error: String? = null,
) {
    /** Human-readable channel name for a hit's channel_id, with a safe fallback. */
    fun channelName(channelId: String): String =
        spaces.firstOrNull { it.id == selectedSpaceId }?.channelNames?.get(channelId)
            ?: spaces.firstNotNullOfOrNull { it.channelNames[channelId] }
            ?: "channel"
}

class SearchViewModel(
    private val repo: app.pigeonsms.data.SocialRepository,
    private val api: app.pigeonsms.network.PigeonApi,
) : androidx.lifecycle.ViewModel() {
    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(SearchUiState())
    val ui: kotlinx.coroutines.flow.StateFlow<SearchUiState> = _ui

    private var searchJob: kotlinx.coroutines.Job? = null

    fun mediaUrl(key: String): String = api.mediaUrl(key)

    fun loadSpaces() {
        if (_ui.value.spaces.isNotEmpty() || _ui.value.loadingSpaces) return
        _ui.update { it.copy(loadingSpaces = true) }
        androidx.lifecycle.viewModelScope.launch {
            runCatching { repo.spaces() }
                .onSuccess { spaces ->
                    val mapped = spaces.map { s ->
                        SearchSpace(
                            id = s.id,
                            name = s.name,
                            channelNames = s.channels.associate { ch -> ch.id to (ch.name ?: "channel") },
                        )
                    }
                    _ui.update {
                        it.copy(
                            spaces = mapped,
                            selectedSpaceId = it.selectedSpaceId ?: mapped.firstOrNull()?.id,
                            loadingSpaces = false,
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loadingSpaces = false, error = e.message?.takeIf { m -> m.isNotBlank() } ?: "couldn't load nests") }
                }
        }
    }

    fun selectSpace(spaceId: String) {
        if (spaceId == _ui.value.selectedSpaceId) return
        // Switching nests invalidates the current hits; the screen re-runs search.
        _ui.update { it.copy(selectedSpaceId = spaceId, results = emptyList(), error = null) }
    }

    fun clearResults() {
        searchJob?.cancel()
        _ui.update { it.copy(searching = false, results = emptyList(), error = null) }
    }

    fun search(query: String) {
        val q = query.trim()
        val spaceId = _ui.value.selectedSpaceId
        if (q.length < 2 || spaceId == null) {
            clearResults()
            return
        }
        searchJob?.cancel()
        _ui.update { it.copy(searching = true, error = null) }
        searchJob = androidx.lifecycle.viewModelScope.launch {
            runCatching { api.searchSpace(spaceId, q) }
                .onSuccess { resp ->
                    _ui.update { it.copy(searching = false, results = resp.results, error = null) }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@onFailure
                    _ui.update { it.copy(searching = false, error = e.message?.takeIf { m -> m.isNotBlank() } ?: "search failed") }
                }
        }
    }
}
