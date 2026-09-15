package com.micon.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.core.auth.AuthTokenStore
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.backend.AuthRepository
import com.micon.app.data.backend.AuthRequestException
import com.micon.app.data.backend.AuthSessionExpiredException
import com.micon.app.data.preferences.PreferenceRepository
import com.micon.app.data.preferences.PreferenceSessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthFormState(
    val submitting: Boolean = false,
    val authenticated: Boolean = false,
    val signupCompleted: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthFormState())
    val state = mutableState.asStateFlow()

    fun submit(email: String, password: String, signup: Boolean) {
        if (mutableState.value.submitting || mutableState.value.authenticated || mutableState.value.signupCompleted) return
        mutableState.value = AuthFormState(submitting = true)
        viewModelScope.launch {
            try {
                if (signup) {
                    repository.signup(email, password)
                    mutableState.value = AuthFormState(authenticated = true)
                } else {
                    repository.login(email, password)
                    mutableState.value = AuthFormState(authenticated = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = AuthFormState(error = if (error is AuthRequestException) error.message
                    else "연결을 확인한 뒤 다시 시도해주세요.")
            }
        }
    }
}

enum class SessionDestination { LOGIN, SURVEY, MAIN }
data class SessionGateState(val loading: Boolean = true, val destination: SessionDestination? = null, val error: String? = null)

class SessionGateViewModel(
    private val auth: AuthRepository,
    private val preferences: PreferenceRepository,
    private val tokens: AuthTokenStore = TokenManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SessionGateState())
    val state = mutableState.asStateFlow()
    private var checking = false

    init { retry() }

    fun retry() {
        if (checking) return
        checking = true
        mutableState.value = SessionGateState()
        viewModelScope.launch {
            val session = tokens.snapshot()
            try {
                if (!session.isLoggedIn) {
                    mutableState.value = SessionGateState(loading = false, destination = SessionDestination.LOGIN)
                    return@launch
                }
                auth.restoreIdentity()
                val preferences = preferences.getPreferences()
                if (tokens.snapshot().generation != session.generation) throw AuthSessionExpiredException()
                mutableState.value = SessionGateState(loading = false, destination =
                    if (preferences.onboardingCompleted) SessionDestination.MAIN else SessionDestination.SURVEY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is AuthSessionExpiredException || error is PreferenceSessionExpiredException) {
                    val current = tokens.snapshot()
                    if (current.generation == session.generation) tokens.clearIfCurrent(current)
                    mutableState.value = SessionGateState(loading = false, destination = SessionDestination.LOGIN)
                } else {
                    mutableState.value = SessionGateState(loading = false,
                        error = "음악 취향을 확인하지 못했어요. 연결을 확인하고 다시 시도해주세요.")
                }
            } finally {
                checking = false
            }
        }
    }
}
