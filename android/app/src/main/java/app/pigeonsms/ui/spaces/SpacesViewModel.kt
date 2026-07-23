package app.pigeonsms.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.network.PigeonApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpaceAction { Create, Join, CreateChannel, Delete, Leave, ChangeIcon, RenameChannel, DeleteChannel }

data class CreatedChannel(
    val id: String,
    val name: String,
    val kind: String = "text",
)

data class SpacesUiState(
    val action: SpaceAction? = null,
    val error: String? = null,
    val createdChannel: CreatedChannel? = null,
)

class SpacesViewModel(
    private val repo: SocialRepository,
    private val api: PigeonApi,
) : ViewModel() {
    private val _ui = MutableStateFlow(SpacesUiState())
    val ui: StateFlow<SpacesUiState> = _ui

    fun clearFeedback() { _ui.update { it.copy(error = null) } }
    fun reportError(message: String) { _ui.update { it.copy(error = message) } }
    fun consumeCreatedChannel() { _ui.update { it.copy(createdChannel = null) } }

    fun create(name: String, onDone: () -> Unit) {
        val cleanName = name.trim()
        when {
            _ui.value.action != null -> return
            cleanName.length < 2 -> {
                _ui.update { it.copy(error = "space name needs at least 2 characters") }
                return
            }
            cleanName.length > 48 -> {
                _ui.update { it.copy(error = "space name must be 48 characters or less") }
                return
            }
        }
        runAction(SpaceAction.Create, "couldn't create space", { repo.createSpace(cleanName) }, onDone)
    }

    fun join(code: String, onDone: () -> Unit) {
        val cleanCode = code.trim().uppercase()
        when {
            _ui.value.action != null -> return
            cleanCode.isBlank() -> {
                _ui.update { it.copy(error = "enter an invite code") }
                return
            }
        }
        runAction(SpaceAction.Join, "couldn't join space", { repo.joinSpace(cleanCode) }, onDone)
    }

    fun createChannel(spaceId: String, name: String, kind: String = "text") {
        val cleanName = name
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        when {
            _ui.value.action != null -> return
            cleanName.length < 2 -> {
                _ui.update { it.copy(error = "channel name needs at least 2 letters or numbers") }
                return
            }
            cleanName.length > 32 -> {
                _ui.update { it.copy(error = "channel name must be 32 characters or less") }
                return
            }
        }
        _ui.value = SpacesUiState(action = SpaceAction.CreateChannel)
        viewModelScope.launch {
            runCatching { api.createChannel(spaceId, cleanName, kind) }
                .onSuccess { channel ->
                    _ui.value = SpacesUiState(
                        createdChannel = CreatedChannel(channel.id, channel.name ?: cleanName, channel.kind),
                    )
                }
                .onFailure { error ->
                    _ui.value = SpacesUiState(
                        error = error.message?.takeIf { it.isNotBlank() } ?: "couldn't create channel",
                    )
                }
        }
    }

    fun invite(spaceId: String, onCode: (String) -> Unit) = viewModelScope.launch {
        runCatching { repo.spaceInvite(spaceId) }
            .onSuccess { onCode(it.code) }
            .onFailure { _ui.update { s -> s.copy(error = "couldn't create invite") } }
    }

    fun changeIcon(spaceId: String, bytes: ByteArray, type: String, onDone: () -> Unit) {
        if (_ui.value.action != null) return
        runAction(SpaceAction.ChangeIcon, "couldn't update the icon", {
            val attachment = repo.uploadFile(bytes, "space-icon", type)
            repo.setSpaceIcon(spaceId, attachment.key)
        }, onDone)
    }

    fun renameChannel(spaceId: String, channelId: String, name: String, onDone: () -> Unit) {
        if (_ui.value.action != null) return
        val cleanName = name
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        when {
            cleanName.length < 2 -> {
                _ui.update { it.copy(error = "channel name needs at least 2 letters or numbers") }
                return
            }
            cleanName.length > 32 -> {
                _ui.update { it.copy(error = "channel name must be 32 characters or less") }
                return
            }
        }
        runAction(SpaceAction.RenameChannel, "couldn't rename channel", { repo.renameChannel(spaceId, channelId, cleanName) }, onDone)
    }

    fun deleteChannel(spaceId: String, channelId: String, onDone: () -> Unit) {
        if (_ui.value.action != null) return
        runAction(SpaceAction.DeleteChannel, "couldn't delete channel", { repo.deleteChannel(spaceId, channelId) }, onDone)
    }

    fun delete(spaceId: String, onDone: () -> Unit) {
        if (_ui.value.action != null) return
        runAction(SpaceAction.Delete, "couldn't delete space", { repo.deleteSpace(spaceId) }, onDone)
    }

    fun leave(spaceId: String, onDone: () -> Unit) {
        if (_ui.value.action != null) return
        runAction(SpaceAction.Leave, "couldn't leave space", { repo.leaveSpace(spaceId) }, onDone)
    }

    private fun runAction(
        action: SpaceAction,
        fallbackError: String,
        operation: suspend () -> Unit,
        onDone: () -> Unit,
    ) {
        _ui.value = SpacesUiState(action = action)
        viewModelScope.launch {
            runCatching { operation() }
                .onSuccess {
                    _ui.value = SpacesUiState()
                    onDone()
                }
                .onFailure { error ->
                    _ui.value = SpacesUiState(error = error.message?.takeIf { it.isNotBlank() } ?: fallbackError)
                }
        }
    }
}
