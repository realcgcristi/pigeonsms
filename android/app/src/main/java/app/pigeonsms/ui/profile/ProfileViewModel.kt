package app.pigeonsms.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.network.MutualSpaceDto
import app.pigeonsms.network.ProfileDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: ProfileDto? = null,
    val mutualSpaces: List<MutualSpaceDto> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val uploading: ProfileMedia? = null,
    val notice: String? = null,
    val error: String? = null,
)

enum class ProfileMedia { Avatar, Banner }

class ProfileViewModel(private val repo: SocialRepository, private val userId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ProfileUiState())
    val ui: StateFlow<ProfileUiState> = _ui
    private var loadGeneration = 0L
    fun mediaUrl(key: String?) = repo.mediaUrl(key)

    /** Block this profile's user (also unfriends server-side), then run [onDone]. */
    fun block(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.block(userId) }
                .onSuccess { onDone() }
                .onFailure { error -> _ui.update { it.copy(error = error.userMessage("couldn't block user")) } }
        }
    }

    init { load() }

    fun load() {
        val generation = ++loadGeneration
        _ui.update {
            it.copy(
                loading = it.profile == null,
                saved = false,
                notice = null,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { repo.profile(userId) }
                .onSuccess { response ->
                    if (generation == loadGeneration) {
                        _ui.update { it.copy(profile = response.profile, mutualSpaces = response.mutual_spaces, loading = false) }
                    }
                }
                .onFailure { error ->
                    if (generation == loadGeneration) {
                        _ui.update {
                            it.copy(loading = false, error = error.userMessage("couldn't load profile"))
                        }
                    }
                }
        }
    }

    fun save(fields: Map<String, String?>) {
        if (_ui.value.saving) return
        val generation = ++loadGeneration
        _ui.update { it.copy(saving = true, saved = false, notice = null, error = null) }
        viewModelScope.launch {
            runCatching {
                repo.updateProfile(fields)
                repo.profile(userId)
            }.onSuccess { response ->
                if (generation != loadGeneration) return@onSuccess
                _ui.update { it.copy(profile = response.profile, mutualSpaces = response.mutual_spaces, saving = false, saved = true, notice = "profile saved") }
            }.onFailure { error ->
                _ui.update { it.copy(saving = false, saved = false, error = error.userMessage("couldn't save profile")) }
            }
        }
    }

    fun uploadAvatar(bytes: ByteArray, type: String, onSuccess: () -> Unit = {}) =
        upload(ProfileMedia.Avatar, bytes, type, onSuccess)

    fun uploadBanner(bytes: ByteArray, type: String, onSuccess: () -> Unit = {}) =
        upload(ProfileMedia.Banner, bytes, type, onSuccess)

    fun resetAvatar(onSuccess: () -> Unit = {}) = remove(ProfileMedia.Avatar, onSuccess)
    fun resetBanner(onSuccess: () -> Unit = {}) = remove(ProfileMedia.Banner, onSuccess)

    private fun upload(media: ProfileMedia, bytes: ByteArray, type: String, onSuccess: () -> Unit) {
        if (_ui.value.saving) return
        val generation = ++loadGeneration
        _ui.update { it.copy(saving = true, saved = false, uploading = media, notice = null, error = null) }
        viewModelScope.launch {
            runCatching {
                when (media) {
                    ProfileMedia.Avatar -> repo.uploadAvatar(bytes, type)
                    ProfileMedia.Banner -> repo.uploadBanner(bytes, type)
                }
                repo.profile(userId)
            }.onSuccess { response ->
                if (generation != loadGeneration) return@onSuccess
                _ui.update {
                    it.copy(
                        profile = response.profile,
                        mutualSpaces = response.mutual_spaces,
                        saving = false,
                        uploading = null,
                        notice = if (media == ProfileMedia.Avatar) "avatar updated" else "banner updated",
                    )
                }
                onSuccess()
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        saving = false,
                        uploading = null,
                        error = error.userMessage(if (media == ProfileMedia.Avatar) "couldn't upload avatar" else "couldn't upload banner"),
                    )
                }
            }
        }
    }

    private fun remove(media: ProfileMedia, onSuccess: () -> Unit) {
        if (_ui.value.saving) return
        val generation = ++loadGeneration
        _ui.update { it.copy(saving = true, saved = false, uploading = media, notice = null, error = null) }
        viewModelScope.launch {
            runCatching {
                when (media) {
                    ProfileMedia.Avatar -> repo.resetAvatar()
                    ProfileMedia.Banner -> repo.resetBanner()
                }
                repo.profile(userId)
            }.onSuccess { response ->
                if (generation != loadGeneration) return@onSuccess
                _ui.update {
                    it.copy(
                        profile = response.profile,
                        mutualSpaces = response.mutual_spaces,
                        saving = false,
                        uploading = null,
                        notice = if (media == ProfileMedia.Avatar) "avatar removed" else "banner removed",
                    )
                }
                onSuccess()
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        saving = false,
                        uploading = null,
                        error = error.userMessage(if (media == ProfileMedia.Avatar) "couldn't remove avatar" else "couldn't remove banner"),
                    )
                }
            }
        }
    }

    fun reportError(message: String) { _ui.update { it.copy(error = message, notice = null) } }
    fun clearFeedback() { _ui.update { it.copy(error = null, notice = null, saved = false) } }
}

private fun Throwable.userMessage(fallback: String) = message?.takeIf { it.isNotBlank() } ?: fallback
