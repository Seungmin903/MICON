package com.micon.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_CURRENT_PASSWORD(HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD", "현재 비밀번호가 올바르지 않습니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "현재 이용할 수 없는 계정입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "유효하지 않은 갱신 토큰입니다."),
    INVALID_RESET_CODE(HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE", "인증 코드가 올바르지 않거나 만료되었습니다."),
    INVALID_PASSWORD_RESET_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD_RESET_TOKEN", "유효하지 않은 비밀번호 재설정 토큰입니다."),
    EMAIL_DELIVERY_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED", "인증 메일을 전송하지 못했습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다."),
    SONG_NOT_FOUND(HttpStatus.NOT_FOUND, "SONG_NOT_FOUND", "곡을 찾을 수 없습니다."),
    VOICE_ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "VOICE_ANALYSIS_NOT_FOUND", "음성 분석 결과를 찾을 수 없습니다."),
    SONG_FIT_EVALUATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "SONG_FIT_EVALUATION_FAILED", "곡 적합도 계산에 실패했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
