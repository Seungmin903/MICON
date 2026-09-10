package com.micon.app.data.backend.dto

/**
 * 백엔드 공통 응답 봉투. `{ code, message, data }` 형식을 가정한다(PickUs 백엔드와 동일).
 * 계약이 다르면 이 클래스만 바꾼다. Moshi 리플렉션 어댑터로 파싱되므로 기본값이 있어야 한다.
 */
data class ApiResponse<T>(
    val code: String = "",
    val message: String = "",
    val data: T? = null,
)

/** UI 계층으로 전달하는 결과 타입. Repository 가 Response/예외를 이 타입으로 변환한다. */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val httpStatus: Int?, val code: String?, val message: String?) : ApiResult<Nothing>()
    data class Error(val throwable: Throwable) : ApiResult<Nothing>()
}

data class TokenPair(val accessToken: String, val refreshToken: String)

data class EmailAuthRequest(
    val email: String,
    val password: String,
    val deviceId: String? = null,
    val deviceName: String? = "MICON Android",
)

data class AuthResponse(
    val userId: Long,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
)

data class CurrentUser(val userId: Long, val email: String)
