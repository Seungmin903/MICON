package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PracticeResultResponse {

	private PracticeResultResponse() {
	}

	public record Report(
		Long practiceId,
		Long songId,
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
