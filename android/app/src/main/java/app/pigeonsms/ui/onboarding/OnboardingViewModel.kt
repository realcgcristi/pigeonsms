package app.pigeonsms.ui.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pigeonsms.data.AuthRepository
import app.pigeonsms.network.PigeonApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { Welcome, Invite, Details, Login }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val invite: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val loginField: String = "",
    val loginPassword: String = "",
    val totp: String = "",
    val needsTotp: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(private val repo: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state

    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "android" }

    fun goTo(step: OnboardingStep) = _state.update { it.copy(step = step, error = null, needsTotp = false, totp = "") }
    fun setInvite(v: String) = _state.update { it.copy(invite = v.uppercase(), error = null) }
    fun setUsername(v: String) = _state.update { it.copy(username = v.lowercase().trim(), error = null) }
    fun setEmail(v: String) = _state.update { it.copy(email = v.trim(), error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setLoginField(v: String) = _state.update { it.copy(loginField = v.trim(), error = null) }
    fun setLoginPassword(v: String) = _state.update { it.copy(loginPassword = v, error = null) }
    fun setTotp(v: String) = _state.update { it.copy(totp = v.filter { c -> c.isDigit() }.take(8), error = null) }

    fun submitInvite() {
        val code = _state.value.invite.trim()
        if (code.isBlank()) return fail("enter your invite code")
        work {
            if (repo.checkInvite(code)) _state.update { it.copy(loading = false, step = OnboardingStep.Details) }
            else fail("that invite is not valid")
        }
    }

    fun submitSignup() {
        val s = _state.value
        if (s.username.length < 3) return fail("username needs at least 3 characters")
        if (!s.email.contains('@')) return fail("that email does not look right")
        if (s.password.length < 8) return fail("password needs at least 8 characters")
        work { repo.signup(s.invite.trim(), s.username, s.email, s.password, deviceName) }
    }

    fun submitLogin() {
        val s = _state.value
        if (s.loginField.isBlank() || s.loginPassword.isBlank()) return fail("fill in both fields")
        if (s.needsTotp && s.totp.length < 6) return fail("enter your 2fa code")
        work {
            try {
                repo.login(s.loginField, s.loginPassword, deviceName, s.totp.ifBlank { null })
            } catch (e: PigeonApiException) {
                if (e.code == "totp_required") {
                    _state.update { it.copy(loading = false, needsTotp = true) }
                    return@work
                }
                throw e
            }
        }
    }

    private fun fail(m: String) = _state.update { it.copy(loading = false, error = m) }

    private fun work(block: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(loading = false) }
            } catch (e: PigeonApiException) {
                fail(e.message)
            } catch (_: Exception) {
                fail("can't reach the roost — check your connection")
            }
        }
    }
}
