package com.micon.backend.domain.setting.api;

import jakarta.validation.constraints.NotNull;

/** 회원 설정 변경 요청 DTO와 필수 입력 검증 규칙을 정의한다. */
public final class SettingRequest {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private SettingRequest() {
	}

	public record Update(
		@NotNull Boolean saveOriginalRecording
	) {
	}
}
