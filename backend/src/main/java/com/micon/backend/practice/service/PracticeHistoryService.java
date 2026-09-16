package com.micon.backend.practice.service;

import com.micon.backend.practice.api.PracticeHistoryResponse;
import com.micon.backend.practice.repository.PracticeHistoryRepository;
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
}
