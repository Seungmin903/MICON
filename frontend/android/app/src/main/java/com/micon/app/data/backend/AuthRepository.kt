package com.micon.app.data.backend

import com.micon.app.core.auth.AuthTokenStore
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.AuthResponse
import com.micon.app.data.backend.dto.EmailAuthRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.Response

class AuthSessionExpiredException : Exception("로그인이 만료됐어요. 다시 로그인해주세요.")
class AuthRequestException(val status: Int?, message: String) : Exception(message)

interface AuthRepository {
    suspend fun login(email: String, password: String)
    suspend fun signup(email: String, password: String)
    suspend fun restoreIdentity()
    suspend fun logout()
}

class NetworkAuthRepository(
    private val api: BackendApi,
    private val tokens: AuthTokenStore = TokenManager,
) : AuthRepository {
    override suspend fun login(email: String, password: String) {
        establishSession(api.login(EmailAuthRequest(email.trim(), password)).authData(signup = false))
    }

    override suspend fun signup(email: String, password: String) {
        establishSession(api.signup(EmailAuthRequest(email.trim(), password)).authData(signup = true))
    }

    private suspend fun establishSession(auth: AuthResponse) {
        currentCoroutineContext().ensureActive()
        if (auth.userId <= 0 || auth.accessToken.isBlank() || auth.refreshToken.isBlank()) {
            throw AuthRequestException(null, "로그인 응답을 확인할 수 없어요. 다시 시도해주세요.")
        }
        tokens.saveSession(auth.userId, auth.accessToken, auth.refreshToken)
    }

    override suspend fun restoreIdentity() {
        val session = tokens.snapshot()
        val response = api.currentUser()
        if (response.code() == 401 || response.code() == 403) throw AuthSessionExpiredException()
        if (!response.isSuccessful) throw AuthRequestException(response.code(), "계정을 확인하지 못했어요. 다시 시도해주세요.")
        val user = response.body()?.data
            ?: throw AuthRequestException(response.code(), "계정 응답을 확인할 수 없어요. 다시 시도해주세요.")
        currentCoroutineContext().ensureActive()
        if (tokens.snapshot().generation != session.generation) throw AuthSessionExpiredException()
        if (user.userId <= 0 || (session.userId > 0 && session.userId != user.userId)) {
            tokens.clearIfCurrent(tokens.snapshot())
            throw AuthSessionExpiredException()
        }
        tokens.saveUserId(user.userId)
    }

    override suspend fun logout() {
        val session = tokens.snapshot()
        try {
            api.logout()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Local sign-out still completes while offline.
        } finally {
            val current = tokens.snapshot()
            if (current.generation == session.generation) tokens.clearIfCurrent(current)
        }
    }

    private fun Response<ApiResponse<AuthResponse>>.authData(signup: Boolean): AuthResponse {
        if (!isSuccessful) {
            throw authFailure(signup)
        }
        return body()?.data ?: throw AuthRequestException(code(), "로그인 응답을 확인할 수 없어요. 다시 시도해주세요.")
    }

    private fun Response<*>.authFailure(signup: Boolean): AuthRequestException {
        val message = when (code()) {
            400, 422 -> "이메일과 비밀번호 형식을 확인해주세요."
            401 -> "이메일 또는 비밀번호가 올바르지 않아요."
            403 -> "로그인할 수 없는 계정이에요. 계정 상태를 확인해주세요."
            409 -> if (signup) "이미 사용 중인 이메일이에요. 로그인해주세요." else "로그인 상태를 확인한 뒤 다시 시도해주세요."
            429 -> "요청이 많아요. 잠시 후 다시 시도해주세요."
            else -> "서버에 연결하지 못했어요. 잠시 후 다시 시도해주세요."
        }
        return AuthRequestException(code(), message)
    }
}
