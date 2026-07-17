package app.pigeonsms.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.data.ThemeMode
import app.pigeonsms.data.ThemeStore
import app.pigeonsms.network.HistoryEntry
import app.pigeonsms.network.SessionDto
import app.pigeonsms.network.TotpSetupResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val sessions: List<SessionDto> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val totpSetup: TotpSetupResponse? = null,
    val recoveryCodes: List<String> = emptyList(),
    val exportJson: String? = null,
    val error: String? = null,
)

class SettingsViewModel(private val auth: AuthRepository, val theme: ThemeStore) : ViewModel() {
    val prefs = theme.prefs
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui

    fun setMode(m: ThemeMode) = viewModelScope.launch { theme.setMode(m) }
    fun setAccent(a: String) = viewModelScope.launch { theme.setAccent(a) }
    fun setReducedMotion(v: Boolean) = viewModelScope.launch { theme.setReducedMotion(v) }
    fun setReadReceipts(v: Boolean) = viewModelScope.launch { theme.setReadReceipts(v) }
    fun setInvisible(v: Boolean) = viewModelScope.launch { theme.setInvisible(v) }
    fun setWallpaper(key: String?) = viewModelScope.launch { theme.setWallpaper(key) }
    fun setWallpaperDim(v: Float) = viewModelScope.launch { theme.setWallpaperDim(v) }
    fun setLiquidGlass(v: Boolean) = viewModelScope.launch { theme.setLiquidGlass(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { theme.setDynamicColor(v) }

    fun loadSessions() = viewModelScope.launch { runCatching { auth.sessions() }.onSuccess { s -> _ui.update { it.copy(sessions = s) } } }
    fun loadHistory() = viewModelScope.launch { runCatching { auth.history() }.onSuccess { h -> _ui.update { it.copy(history = h) } } }
    fun revoke(id: String) = viewModelScope.launch { runCatching { auth.revokeSession(id) }; loadSessions() }
    fun signOut() = viewModelScope.launch { auth.logout() }

    fun startTotp() = viewModelScope.launch { runCatching { auth.totpSetup() }.onSuccess { s -> _ui.update { it.copy(totpSetup = s) } } }
    fun enableTotp(code: String) = viewModelScope.launch {
        runCatching { auth.totpEnable(code) }
            .onSuccess { codes -> _ui.update { it.copy(recoveryCodes = codes, totpSetup = null) } }
            .onFailure { _ui.update { it.copy(error = "wrong code") } }
    }
    fun export() = viewModelScope.launch { runCatching { auth.exportData() }.onSuccess { j -> _ui.update { it.copy(exportJson = j) } } }
    fun deleteAccount(password: String) = viewModelScope.launch { runCatching { auth.deleteAccount(password) } }
}
