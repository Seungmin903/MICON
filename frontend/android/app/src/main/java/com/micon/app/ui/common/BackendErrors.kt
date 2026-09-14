package com.micon.app.ui.common

import com.micon.app.data.backend.AuthSessionExpiredException
import com.micon.app.data.backend.BackendRequestException
import java.io.IOException

/** 백엔드 호출 실패를 화면 문구로. 서버가 준 message 를 우선 쓰고, 네트워크 오류는 공통 문구로 바꾼다. */
fun describeBackendError(e: Throwable, fallback: String = "요청을 처리하지 못했어요."): String = when (e) {
    is AuthSessionExpiredException -> e.message ?: "로그인이 만료됐어요. 다시 로그인해주세요."
    is BackendRequestException -> e.message ?: fallback
    is IOException -> "서버에 연결할 수 없어요. 네트워크를 확인해 주세요."
    else -> e.message ?: fallback
}
