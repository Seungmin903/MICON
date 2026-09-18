package com.micon.backend.domain.practice.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 연습 세션 생성·완료·상태 변경에 사용하는 요청 DTO와 검증 규칙을 정의한다. */
public final class PracticeRequest {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private PracticeRequest() {
	}

	public record Create(
		@NotBlank @Size(max = 64) String songId,
		@Min(1) Long recordingId,
		Long sectionId,
		@Min(-6) @Max(5) Integer selectedKey,
		@DecimalMin("0.50") @DecimalMax("2.00") BigDecimal playbackSpeed,
		PracticeScope practiceScope,
		@PositiveOrZero Integer customStartMs,
		@PositiveOrZero Integer customEndMs
	) {
	}

	public record Complete(
		@NotNull @PositiveOrZero Integer durationMs,
		@Min(1) Long voiceAnalysisId,
		@Min(1) Long recordingId
	) {
	}

	public record StatusUpdate(@NotNull ActiveStatus status) {
	}

	public enum ActiveStatus {
		RECORDING,
		IN_PROGRESS,
		PAUSED
	}

	public enum PracticeScope {
		FULL_SONG,
		SECTION,
		CUSTOM
	}
}
