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
    SETLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "SETLIST_NOT_FOUND", "세트리스트를 찾을 수 없습니다."),
    SONG_NOT_FAVORITED(HttpStatus.BAD_REQUEST, "SONG_NOT_FAVORITED", "찜한 곡만 세트리스트에 추가할 수 있습니다."),
    INVALID_SETLIST_ORDER(HttpStatus.BAD_REQUEST, "INVALID_SETLIST_ORDER", "세트리스트 곡 순서가 올바르지 않습니다."),
    SETLIST_CONFLICT(HttpStatus.CONFLICT, "SETLIST_CONFLICT", "세트리스트가 다른 요청에 의해 변경되었습니다."),
    PRACTICE_ASSET_NOT_READY(HttpStatus.CONFLICT, "PRACTICE_ASSET_NOT_READY", "연습 데이터가 아직 준비되지 않았습니다."),
    UNSUPPORTED_PRACTICE_KEY(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_PRACTICE_KEY", "지원하지 않는 연습 키입니다."),
    PRACTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "PRACTICE_NOT_FOUND", "연습 기록을 찾을 수 없습니다."),
    INVALID_PRACTICE_STATE(HttpStatus.CONFLICT, "INVALID_PRACTICE_STATE", "현재 상태에서는 요청한 연습 작업을 수행할 수 없습니다."),
    PRACTICE_RESULT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRACTICE_RESULT_NOT_FOUND", "연습 결과를 찾을 수 없습니다."),
    VOICE_ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "VOICE_ANALYSIS_NOT_FOUND", "음성 분석 결과를 찾을 수 없습니다."),
    VOICE_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "VOICE_SESSION_NOT_FOUND", "음성 분석 세션을 찾을 수 없습니다."),
    VOICE_SESSION_EXPIRED(HttpStatus.GONE, "VOICE_SESSION_EXPIRED", "음성 분석 세션이 만료되었습니다."),
    INVALID_GUEST_SESSION(HttpStatus.UNAUTHORIZED, "INVALID_GUEST_SESSION", "유효하지 않은 비회원 세션입니다."),
    RECORDING_NOT_FOUND(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "녹음 파일을 찾을 수 없습니다."),
    INVALID_RECORDING_FILE(HttpStatus.BAD_REQUEST, "INVALID_RECORDING_FILE", "지원하지 않거나 올바르지 않은 녹음 파일입니다."),
    RECORDING_UPLOAD_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "RECORDING_UPLOAD_FAILED", "녹음 파일을 저장하지 못했습니다."),
    RECORDING_DELETE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "RECORDING_DELETE_FAILED", "녹음 파일을 삭제하지 못했습니다."),
    VOICE_DATA_DELETION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_DATA_DELETION_FAILED", "음성 데이터를 삭제하지 못했습니다."),
    VOICE_DATA_DELETION_NOT_FOUND(HttpStatus.NOT_FOUND, "VOICE_DATA_DELETION_NOT_FOUND", "삭제할 음성 데이터를 찾을 수 없습니다."),
    RECORDING_NOT_READY(HttpStatus.CONFLICT, "RECORDING_NOT_READY", "업로드가 완료된 녹음만 분석할 수 있습니다."),
    VOICE_ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "VOICE_ANALYSIS_IN_PROGRESS", "해당 녹음의 분석이 이미 진행 중입니다."),
    VOICE_ANALYSIS_DISPATCH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_ANALYSIS_DISPATCH_FAILED", "음성 분석 작업을 요청하지 못했습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 requestId에 다른 음성 파일을 사용할 수 없습니다."),
    AUDIO_EXPIRED(HttpStatus.GONE, "AUDIO_EXPIRED", "AI 서버의 임시 음성 파일이 만료되어 다시 업로드해야 합니다."),
    INVALID_VOICE_ANALYSIS_STATE(HttpStatus.CONFLICT, "INVALID_VOICE_ANALYSIS_STATE", "현재 상태에서는 요청한 음성 분석 작업을 수행할 수 없습니다."),
    INVALID_AI_SERVICE_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_AI_SERVICE_TOKEN", "AI 서비스 인증에 실패했습니다."),
    INVALID_VOICE_ANALYSIS_CALLBACK(HttpStatus.CONFLICT, "INVALID_VOICE_ANALYSIS_CALLBACK", "처리할 수 없는 음성 분석 결과입니다."),
    RECOMMENDATION_INPUT_NOT_READY(HttpStatus.CONFLICT, "RECOMMENDATION_INPUT_NOT_READY", "완료된 음성 분석과 사용자 임베딩이 필요합니다."),
    RECOMMENDATION_ALREADY_GENERATING(HttpStatus.CONFLICT, "RECOMMENDATION_ALREADY_GENERATING", "해당 음성 분석의 추천을 생성하고 있습니다."),
    RECOMMENDATION_DISPATCH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "RECOMMENDATION_DISPATCH_FAILED", "추천 작업을 요청하지 못했습니다."),
    RECOMMENDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_NOT_FOUND", "추천 결과를 찾을 수 없습니다."),
    RECOMMENDATION_SONG_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION_SONG_NOT_FOUND", "추천 결과에서 곡을 찾을 수 없습니다."),
    SONG_FIT_EVALUATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "SONG_FIT_EVALUATION_FAILED", "곡 적합도 계산에 실패했습니다."),
    EMBEDDING_CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_CATALOG_UNAVAILABLE", "사용 가능한 임베딩 카탈로그가 없습니다."),
    CATALOG_MEDIA_NOT_READY(HttpStatus.CONFLICT, "CATALOG_MEDIA_NOT_READY", "사용 가능한 곡 미디어가 없습니다."),
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
