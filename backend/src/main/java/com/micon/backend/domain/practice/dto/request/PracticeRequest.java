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
		@Min(-3) @Max(3) Integer selectedKey,
		@DecimalMin("0.50") @DecimalMax("2.00") BigDecimal playbackSpeed,
		PracticeScope practiceScope,
		@PositiveOrZero Integer customStartMs,
		@PositiveOrZero Integer customEndMs
	) {
	}

	public record Complete(
		@NotNull @PositiveOrZero Integer durationMs,
		@Min(1) Long voiceAnalysisId,
		@Min(1) Long recordingId,
		@Min(-3) @Max(3) Integer finalSelectedKey,
		@PositiveOrZero Integer keyChangeCount
	) {
		public Complete(Integer durationMs, Long voiceAnalysisId, Long recordingId) {
			this(durationMs, voiceAnalysisId, recordingId, null, null);
		}
	}

	public record StatusUpdate(@NotNull ActiveStatus status) {
	}

	/** 연습 중 현재 선택 키를 저장한다. 키 범위는 Android 실시간 피치 변환 범위와 맞춘다. */
	public record KeyUpdate(@NotNull @Min(-3) @Max(3) Integer selectedKey) {
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
