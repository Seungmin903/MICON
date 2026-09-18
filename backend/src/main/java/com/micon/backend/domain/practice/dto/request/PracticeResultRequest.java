package com.micon.backend.domain.practice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 전체 및 구간별 연습 평가를 저장할 때 사용하는 요청 DTO와 점수 검증 규칙을 정의한다. */
public final class PracticeResultRequest {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
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
