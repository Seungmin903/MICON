package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 연습 세션의 실행 조건과 현재 상태를 반환하는 응답 DTO를 정의한다. */
public final class PracticeResponse {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private PracticeResponse() {
	}

	public record Detail(
		Long practiceId,
		String songId,
		String songTitle,
		Long sectionId,
		Long recordingId,
		Long voiceAnalysisId,
		int initialKey,
		int selectedKey,
		int keyChangeCount,
		boolean keyChanged,
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
