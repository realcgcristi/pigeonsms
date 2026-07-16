package app.pigeonsms.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.SocialRepository
import app.pigeonsms.network.PigeonApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FriendsViewModel(private val repo: SocialRepository) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding
    fun clearMessage() { _message.value = null }

    fun add(username: String, onDone: () -> Unit) {
        if (_adding.value) return
        val clean = username.trim().lowercase()
        if (clean.length < 2) {
            _message.value = "enter a username"
            return
        }
        _adding.value = true
        _message.value = null
        viewModelScope.launch {
            try { repo.addFriend(clean); _adding.value = false; onDone() }
            catch (e: PigeonApiException) { _message.value = e.message; _adding.value = false }
            catch (_: Exception) { _message.value = "couldn't send"; _adding.value = false }
        }
    }
    fun accept(id: String, onDone: () -> Unit) = viewModelScope.launch { runCatching { repo.acceptFriend(id) }; onDone() }
    fun remove(id: String, onDone: () -> Unit) = viewModelScope.launch { runCatching { repo.removeFriend(id) }; onDone() }
    fun openDm(userId: String, onOpen: (String) -> Unit) = viewModelScope.launch { runCatching { repo.openDm(userId) }.onSuccess(onOpen) }
}
