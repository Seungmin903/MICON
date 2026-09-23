package com.micon.backend.domain.practice.service;

import com.micon.backend.domain.practice.dto.response.PracticeHistoryResponse;
import com.micon.backend.domain.practice.dto.response.PracticeScoreTrendResponse;
import com.micon.backend.domain.practice.repository.PracticeHistoryRepository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원의 연습 기록을 날짜와 페이지 조건으로 조회해 목록 응답을 만든다. */
@Service
public class PracticeHistoryService {

	private final PracticeHistoryRepository repository;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeHistoryService 객체를 생성한다.
	public PracticeHistoryService(PracticeHistoryRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	// 기능: getHistory 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PracticeHistoryResponse.Page getHistory(Long userId, LocalDate date, int page, int size) {
		long totalElements = repository.count(userId, date);
		List<PracticeHistoryResponse.Summary> content = totalElements == 0
			? List.of()
			: repository.findPage(userId, date, size, (long) page * size);
		int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
		return new PracticeHistoryResponse.Page(content, page, size, totalElements, totalPages);
	}

	@Transactional(readOnly = true)
	public PracticeScoreTrendResponse getScoreTrend(Long userId, String songId, int limit) {
		List<PracticeScoreTrendResponse.Point> points = repository.findScoreTrend(userId, songId, limit);
		if (points.isEmpty()) {
			return new PracticeScoreTrendResponse(songId, 0, null, null, null, null, points);
		}

		var firstScore = points.get(0).overallScore();
		var latestScore = points.get(points.size() - 1).overallScore();
		var bestScore = points.stream()
			.map(PracticeScoreTrendResponse.Point::overallScore)
			.max(java.math.BigDecimal::compareTo)
			.orElseThrow();
		return new PracticeScoreTrendResponse(
			songId,
			points.size(),
			firstScore,
			latestScore,
			bestScore,
			latestScore.subtract(firstScore),
			points
		);
	}
}
