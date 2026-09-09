package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class PracticeResponse {

	private PracticeResponse() {
	}

	public record Detail(
		Long practiceId,
		Long songId,
		String songTitle,
		Long sectionId,
		Long recordingId,
		Long voiceAnalysisId,
		int selectedKey,
		BigDecimal playbackSpeed,
		Integer customStartMs,
		Integer customEndMs,
		String practiceScope,
		String status,
		LocalDateTime startedAt,
		LocalDateTime endedAt,
		Integer durationMs,
		LocalDateTime createdAt
	) {
	}
}
