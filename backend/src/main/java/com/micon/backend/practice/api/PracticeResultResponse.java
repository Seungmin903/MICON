package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 연습 리포트, 분석 상태, 취약 구간 재연습 정보를 반환하는 응답 DTO를 모아 둔다. */
public final class PracticeResultResponse {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private PracticeResultResponse() {
	}

	public record Report(
		Long practiceId,
		String songId,
		String songTitle,
		int selectedKey,
		String practiceScope,
		Long sectionId,
		Integer durationMs,
		String status,
		BigDecimal overallScore,
		BigDecimal pitchScore,
		BigDecimal sustainScore,
		BigDecimal rhythmScore,
		String summaryText,
		List<PracticeResultRequest.SectionEvaluation> sections,
		RetrySection weakSection,
		LocalDateTime analyzedAt
	) {
	}

	public record RetrySection(Long sectionId, int selectedKey, BigDecimal score) {
	}

	public record Analysis(
		Long practiceId,
		String status,
		boolean retryable
	) {
	}
}
