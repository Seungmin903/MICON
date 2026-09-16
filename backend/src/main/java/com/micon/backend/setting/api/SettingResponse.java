package com.micon.backend.setting.api;

/** 회원의 원본 녹음 저장 여부를 반환하는 설정 응답이다. */
public record SettingResponse(boolean saveOriginalRecording) {
}
