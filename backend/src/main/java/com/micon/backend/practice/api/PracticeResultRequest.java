package com.micon.backend.practice.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class PracticeResultRequest {

	private PracticeResultRequest() {
	}

	public record Save(
		@NotNull ResultStatus status,
		@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal overallScore,
		@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal pitchScore,
		@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal sustainScore,
		@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal rhythmScore,
		@Size(max = 500) String summaryText,
		@Size(max = 20) List<@Valid SectionEvaluation> sections
	) {
	}

	public record SectionEvaluation(
		@NotNull Long sectionId,
		@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal score,
		@NotNull Boolean measured,
		@Size(max = 120) String summary
	) {
	}

	public enum ResultStatus {
		COMPLETED,
		INSUFFICIENT_DATA,
		FAILED
	}
}
