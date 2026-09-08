package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PracticeHistoryResponse {

	private PracticeHistoryResponse() {
	}

	public record Page(
		List<Summary> content,
		int page,
		int size,
		long totalElements,
		int totalPages
	) {
	}

	public record Summary(
		Long practiceId,
		Long songId,
		String songTitle,
		String imageUrl,
		int selectedKey,
		String practiceScope,
		Long sectionId,
		String status,
		LocalDateTime startedAt,
		Integer durationMs,
		String resultStatus,
		BigDecimal overallScore
	) {
	}
}
