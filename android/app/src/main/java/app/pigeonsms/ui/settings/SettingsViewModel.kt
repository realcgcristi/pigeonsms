package app.pigeonsms.ui.settings

import android.app.Activity
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.data.ThemeMode
import app.pigeonsms.data.ThemeStore
import app.pigeonsms.network.HistoryEntry
import app.pigeonsms.network.PairingDto
import app.pigeonsms.network.PairingInviteDto
import app.pigeonsms.network.PasskeyDto
import app.pigeonsms.network.SessionDto
import app.pigeonsms.network.TotpSetupResponse
import app.pigeonsms.security.AndroidPasskeys
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val sessions: List<SessionDto> = emptyList(),
    val passkeys: List<PasskeyDto> = emptyList(),
    val pairings: List<PairingDto> = emptyList(),
    val activePairing: PairingInviteDto? = null,
    val history: List<HistoryEntry> = emptyList(),
    val totpSetup: TotpSetupResponse? = null,
    val recoveryCodes: List<String> = emptyList(),
    val exportJson: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

class SettingsViewModel(private val auth: AuthRepository, val theme: ThemeStore) : ViewModel() {
    val prefs = theme.prefs
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "android" }

    fun setMode(m: ThemeMode) = viewModelScope.launch { theme.setMode(m) }
    fun setAccent(a: String) = viewModelScope.launch { theme.setAccent(a) }
    fun setReducedMotion(v: Boolean) = viewModelScope.launch { theme.setReducedMotion(v) }
    fun setReadReceipts(v: Boolean) = viewModelScope.launch { theme.setReadReceipts(v) }
    fun setInvisible(v: Boolean) = viewModelScope.launch { theme.setInvisible(v) }
    fun setE2ee(v: Boolean) = viewModelScope.launch { theme.setE2ee(v) }
    fun setWallpaper(key: String?) = viewModelScope.launch { theme.setWallpaper(key) }
    fun setWallpaperDim(v: Float) = viewModelScope.launch { theme.setWallpaperDim(v) }
    fun setLiquidGlass(v: Boolean) = viewModelScope.launch { theme.setLiquidGlass(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { theme.setDynamicColor(v) }
    fun setExperimentalRedesign(v: Boolean) = viewModelScope.launch { theme.setExperimentalRedesign(v) }
    fun setUiSkin(skin: String) = viewModelScope.launch { theme.setUiSkin(skin) }

    fun loadSessions() = viewModelScope.launch { runCatching { auth.sessions() }.onSuccess { s -> _ui.update { it.copy(sessions = s) } } }
    fun loadDevices() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, error = null) }
        runCatching {
            coroutineScope {
                val sessions = async { auth.sessions() }
                val passkeys = async { auth.passkeys() }
                val pairings = async { auth.pairings() }
                Triple(sessions.await(), passkeys.await(), pairings.await())
            }
        }.onSuccess { (sessions, passkeys, pairings) ->
            _ui.update {
                it.copy(
                    sessions = sessions,
                    passkeys = passkeys,
                    pairings = pairings,
                    activePairing = activePairingAfterRefresh(it.activePairing, pairings),
                    busy = false,
                )
            }
        }.onFailure { error ->
            _ui.update { it.copy(busy = false, error = error.message ?: "could not load trusted devices") }
        }
    }
    fun refreshPairings() = viewModelScope.launch {
        runCatching { auth.pairings() }.onSuccess { pairings ->
            _ui.update {
                it.copy(
                    pairings = pairings,
                    activePairing = activePairingAfterRefresh(it.activePairing, pairings),
                )
            }
        }
    }
    fun registerPasskey(activity: Activity) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, error = null, notice = null) }
        runCatching { AndroidPasskeys.register(activity, auth, "$deviceName passkey") }
            .onSuccess {
                _ui.update { it.copy(busy = false, notice = "passkey created") }
                loadDevices()
            }
            .onFailure { error ->
                _ui.update { it.copy(busy = false, error = AndroidPasskeys.message(error)) }
            }
    }
    fun revokePasskey(id: String) = viewModelScope.launch {
        runCatching { auth.revokePasskey(id) }
            .onSuccess { loadDevices() }
            .onFailure { error -> _ui.update { it.copy(error = error.message ?: "could not remove passkey") } }
    }
    fun createPairing() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, error = null, notice = null) }
        runCatching { auth.createPairing() }
            .onSuccess { pairing ->
                _ui.update { it.copy(activePairing = pairing, busy = false) }
                refreshPairings()
            }
            .onFailure { error -> _ui.update { it.copy(busy = false, error = error.message ?: "could not create pairing") } }
    }
    fun approvePairing(id: String) = viewModelScope.launch {
        runCatching { auth.approvePairing(id) }
            .onSuccess { refreshPairings() }
            .onFailure { error -> _ui.update { it.copy(error = error.message ?: "could not approve device") } }
    }
    fun denyPairing(id: String) = viewModelScope.launch {
        runCatching { auth.denyPairing(id) }
            .onSuccess { refreshPairings() }
            .onFailure { error -> _ui.update { it.copy(error = error.message ?: "could not deny device") } }
    }
    fun cancelPairing(id: String) = viewModelScope.launch {
        runCatching { auth.cancelPairing(id) }
        _ui.update { it.copy(activePairing = null) }
        refreshPairings()
    }
    fun dismissPairing() = _ui.update { it.copy(activePairing = null) }
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

    private fun activePairingAfterRefresh(active: PairingInviteDto?, pairings: List<PairingDto>): PairingInviteDto? {
        if (active == null) return null
        val status = pairings.firstOrNull { it.id == active.id }?.status ?: return active
        return if (status in setOf("claimed", "denied", "cancelled", "expired")) null else active
    }
}
