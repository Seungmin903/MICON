package com.micon.backend.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.practice.api.PracticeResponse;
import com.micon.backend.practice.api.PracticeResultRequest;
import com.micon.backend.practice.api.PracticeResultRequest.ResultStatus;
import com.micon.backend.practice.api.PracticeResultRequest.SectionEvaluation;
import com.micon.backend.practice.repository.PracticeRepository;
import com.micon.backend.practice.repository.PracticeResultRepository;
import com.micon.backend.practice.repository.PracticeResultRepository.ResultRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PracticeResultServiceTest {

	@Mock
	private PracticeRepository practiceRepository;
	@Mock
	private PracticeResultRepository resultRepository;

	private PracticeResultService service;

	@BeforeEach
	void setUp() {
		service = new PracticeResultService(practiceRepository, resultRepository, new ObjectMapper());
	}

	@Test
	void storesScoresAndReturnsLowestMeasuredSectionForRetry() {
		var practice = practice("COMPLETED");
		var sections = List.of(
			new SectionEvaluation(31L, decimal("82"), true, "안정적"),
			new SectionEvaluation(32L, decimal("54"), true, "고음 보완 필요")
		);
		var request = new PracticeResultRequest.Save(
			ResultStatus.COMPLETED, decimal("70"), decimal("65"), decimal("75"), decimal("72"), "좋은 연습", sections
		);
		String storedSummary = new ObjectMapper().writeValueAsString(
			new PracticeResultService.SummaryPayload("좋은 연습", sections)
		);
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice));
		when(practiceRepository.sectionBelongsToSong(31L, "10")).thenReturn(true);
		when(practiceRepository.sectionBelongsToSong(32L, "10")).thenReturn(true);
		when(resultRepository.findLatest(50L)).thenReturn(Optional.of(
			new ResultRow(60L, 50L, "COMPLETED", decimal("70"), decimal("65"),
				decimal("75"), decimal("72"), storedSummary, LocalDateTime.now(), LocalDateTime.now())
		));

		var report = service.save(7L, 50L, request);

		assertThat(report.weakSection().sectionId()).isEqualTo(32L);
		assertThat(report.weakSection().selectedKey()).isEqualTo(-2);
		assertThat(report.sections()).hasSize(2);
	}

	@Test
	void acceptsCompletedResultWithoutSustainScore() {
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice("COMPLETED")));
		when(resultRepository.findLatest(50L)).thenReturn(Optional.of(
			new ResultRow(60L, 50L, "COMPLETED", decimal("70"), decimal("65"),
				null, decimal("72"), "{\"summaryText\":null,\"sections\":[]}",
				LocalDateTime.now(), LocalDateTime.now())
		));
		var request = new PracticeResultRequest.Save(
			ResultStatus.COMPLETED, decimal("70"), decimal("65"), null, decimal("72"), null, List.of()
		);

		assertThat(service.save(7L, 50L, request).sustainScore()).isNull();
	}

	@Test
	void rejectsResultBeforePracticeCompletion() {
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice("IN_PROGRESS")));
		var request = new PracticeResultRequest.Save(
			ResultStatus.INSUFFICIENT_DATA, null, null, null, null, "데이터 부족", List.of()
		);

		assertThatThrownBy(() -> service.save(7L, 50L, request))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRACTICE_STATE));
	}

	@Test
	void reportsAnalyzingWhileCompletedPracticeWaitsForFirstResult() {
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice("COMPLETED")));
		when(resultRepository.findLatest(50L)).thenReturn(Optional.empty());

		var analysis = service.analysis(7L, 50L);

		assertThat(analysis.status()).isEqualTo("ANALYZING");
		assertThat(analysis.retryable()).isFalse();
	}

	@Test
	void retriesOnlyFailedAnalysis() {
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice("COMPLETED")));
		when(resultRepository.findLatest(50L)).thenReturn(Optional.of(
			new ResultRow(60L, 50L, "FAILED", null, null, null, null, null, null, LocalDateTime.now())
		));
		when(resultRepository.retry(60L)).thenReturn(1);

		assertThat(service.retry(7L, 50L).status()).isEqualTo("ANALYZING");
	}

	private PracticeResponse.Detail practice(String status) {
		return new PracticeResponse.Detail(
			50L, "10", "연습곡", null, 20L, null, -2, -2, 0, false, new BigDecimal("1.00"),
			null, null, "FULL_SONG", status, LocalDateTime.now(), LocalDateTime.now(), 120_000, LocalDateTime.now()
		);
	}

	private BigDecimal decimal(String value) {
		return new BigDecimal(value);
	}
}
