package app.pigeonsms.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.network.SpaceEmojiDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NestEmojiUiState(
    val emoji: List<SpaceEmojiDto> = emptyList(),
    val loading: Boolean = true,
    /** True while a create/delete is in flight — gates the confirm buttons. */
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Backing state for [NestEmojiScreen] (2.9.5).
 *
 * Creation is two calls — upload the bytes, then register the returned key as an
 * emoji — because that reuses the media pipeline's existing size/type enforcement
 * instead of adding a second upload path. If the second call fails, the upload is
 * simply orphaned in R2 rather than leaving a row pointing at nothing.
 */
class NestEmojiViewModel(private val repo: SocialRepository) : ViewModel() {
    private val _ui = MutableStateFlow(NestEmojiUiState())
    val ui: StateFlow<NestEmojiUiState> = _ui

    fun mediaUrl(key: String): String? = repo.mediaUrl(key)

    fun reportError(message: String) = _ui.update { it.copy(error = message) }

    fun load(spaceId: String, refresh: Boolean = false) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.spaceEmojis(spaceId, refresh = refresh) }
                .onSuccess { list -> _ui.update { it.copy(emoji = list, loading = false) } }
                .onFailure { error ->
                    _ui.update {
                        it.copy(loading = false, error = error.message ?: "couldn't load emoji")
                    }
                }
        }
    }

    fun create(
        spaceId: String,
        name: String,
        kind: String,
        bytes: ByteArray,
        contentType: String,
        onDone: () -> Unit,
    ) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val result = runCatching {
                // Extension is cosmetic here — the server sniffs the real type —
                // but a sensible filename keeps the media list readable.
                val extension = contentType.substringAfter('/', "png")
                val uploaded = repo.uploadFile(bytes, "$name.$extension", contentType)
                repo.createSpaceEmoji(spaceId, name, uploaded.key, kind, contentType)
            }
            result
                .onSuccess {
                    _ui.update { it.copy(busy = false) }
                    onDone()
                    load(spaceId, refresh = true)
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(busy = false, error = error.message ?: "couldn't add that emoji")
                    }
                }
        }
    }

    fun delete(spaceId: String, emojiId: String, onDone: () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            runCatching { repo.deleteSpaceEmoji(spaceId, emojiId) }
                .onSuccess {
                    _ui.update { state ->
                        state.copy(busy = false, emoji = state.emoji.filterNot { it.id == emojiId })
                    }
                    onDone()
                }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false, error = error.message ?: "couldn't delete that emoji") }
                }
        }
    }
}
