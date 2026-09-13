package com.micon.backend.practice.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public final class PracticeRequest {

	private PracticeRequest() {
	}

	public record Create(
		@NotNull @Min(1) Long songId,
		@Min(1) Long recordingId,
		Long sectionId,
		@Min(-12) @Max(12) Integer selectedKey,
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
