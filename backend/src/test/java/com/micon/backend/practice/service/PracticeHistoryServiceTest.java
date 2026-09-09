package com.micon.backend.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.micon.backend.practice.api.PracticeHistoryResponse;
import com.micon.backend.practice.repository.PracticeHistoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PracticeHistoryServiceTest {

	@Mock
	private PracticeHistoryRepository repository;

	private PracticeHistoryService service;

	@BeforeEach
	void setUp() {
		service = new PracticeHistoryService(repository);
	}

	@Test
	void returnsRequestedUsersDatedHistoryWithPagination() {
		LocalDate date = LocalDate.of(2026, 9, 8);
		var summary = new PracticeHistoryResponse.Summary(
			50L, 10L, "연습곡", "/images/10.jpg", -2, "FULL_SONG", null,
			"COMPLETED", LocalDateTime.of(2026, 9, 8, 12, 0), 120_000,
			"COMPLETED", new BigDecimal("78.50")
		);
		when(repository.count(7L, date)).thenReturn(21L);
		when(repository.findPage(7L, date, 20, 20L)).thenReturn(List.of(summary));

		var response = service.getHistory(7L, date, 1, 20);

		assertThat(response.content()).containsExactly(summary);
		assertThat(response.totalElements()).isEqualTo(21L);
		assertThat(response.totalPages()).isEqualTo(2);
	}

	@Test
	void returnsEmptyPageWithoutRunningListQuery() {
		when(repository.count(7L, null)).thenReturn(0L);

		var response = service.getHistory(7L, null, 0, 20);

		assertThat(response.content()).isEmpty();
		assertThat(response.totalPages()).isZero();
	}
}
