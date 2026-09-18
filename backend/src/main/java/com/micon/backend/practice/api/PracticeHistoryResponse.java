package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 연습 기록 목록의 페이지 정보와 각 연습 요약 항목을 정의한다. */
public final class PracticeHistoryResponse {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
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
		String songId,
		String songTitle,
		String imageUrl,
		int initialKey,
		int selectedKey,
		int keyChangeCount,
		boolean keyChanged,
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
