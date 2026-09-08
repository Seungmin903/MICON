package com.micon.backend.practice.service;

import com.micon.backend.practice.api.PracticeHistoryResponse;
import com.micon.backend.practice.repository.PracticeHistoryRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeHistoryService {

	private final PracticeHistoryRepository repository;

	public PracticeHistoryService(PracticeHistoryRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public PracticeHistoryResponse.Page getHistory(Long userId, LocalDate date, int page, int size) {
		long totalElements = repository.count(userId, date);
		List<PracticeHistoryResponse.Summary> content = totalElements == 0
			? List.of()
			: repository.findPage(userId, date, size, (long) page * size);
		int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
		return new PracticeHistoryResponse.Page(content, page, size, totalElements, totalPages);
	}
}
