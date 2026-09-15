package com.micon.app.data.backend

import com.micon.app.core.network.Json
import com.micon.app.data.backend.dto.ApiResponse
import com.squareup.moshi.Types
import retrofit2.Response

/** 백엔드 `{code,message,data}` 봉투 처리. 401/403 은 세션 만료, 그 외 실패는 [BackendRequestException](서버 code·message 유지). */
internal fun <T> Response<ApiResponse<T>>.unwrapData(): T {
    if (code() == 401 || code() == 403) throw AuthSessionExpiredException()
    if (!isSuccessful) {
        val error = errorEnvelope()
        throw BackendRequestException(code(), error?.code, error?.message ?: "서버 요청에 실패했습니다 (HTTP ${code()}).")
    }
    return body()?.data ?: throw BackendRequestException(code(), body()?.code, "서버 응답에 데이터가 없습니다.")
}

/** 성공했지만 data 가 null 일 수 있는 응답(예: 목록의 "없음"). 실패는 [unwrapData] 와 같게 던진다. */
internal fun <T> Response<ApiResponse<T>>.unwrapNullable(): T? {
    if (code() == 401 || code() == 403) throw AuthSessionExpiredException()
    if (!isSuccessful) {
        val error = errorEnvelope()
        throw BackendRequestException(code(), error?.code, error?.message ?: "서버 요청에 실패했습니다 (HTTP ${code()}).")
    }
    return body()?.data
}

internal fun Response<*>.errorEnvelope(): ApiResponse<Any>? {
    val raw = errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { ERROR_ADAPTER.fromJson(raw) }.getOrNull()
}

private val ERROR_ADAPTER = Json.moshi.adapter<ApiResponse<Any>>(Types.newParameterizedType(ApiResponse::class.java, Any::class.java))
