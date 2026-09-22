package com.micon.backend.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.practice.dto.response.PracticeResponse;
import com.micon.backend.domain.practice.dto.request.PracticeResultRequest;
import com.micon.backend.domain.practice.dto.request.PracticeResultRequest.ResultStatus;
import com.micon.backend.domain.practice.dto.request.PracticeResultRequest.SectionEvaluation;
import com.micon.backend.domain.practice.repository.PracticeRepository;
import com.micon.backend.domain.practice.repository.PracticeResultRepository;
import com.micon.backend.domain.practice.repository.PracticeResultRepository.ResultRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.micon.backend.domain.practice.service.PracticeResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
	void storesDetailedDeviceJudgementsAndPitchCurve() throws Exception {
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice("COMPLETED")));
		when(resultRepository.findLatest(50L)).thenReturn(Optional.of(
			new ResultRow(60L, 50L, "COMPLETED", decimal("70"), decimal("65"), decimal("75"), decimal("72"),
				"{\"summaryText\":null,\"sections\":[]}", LocalDateTime.now(), LocalDateTime.now())
		));
		var notes = List.of(new PracticeResultRequest.NoteEvaluation(3, "GOOD", 12, 0.08));
		var curve = new PracticeResultRequest.PitchCurve(100, 0, java.util.Arrays.asList(60.25, null, 61.0));
		var request = new PracticeResultRequest.Save(
			ResultStatus.COMPLETED, decimal("70"), decimal("65"), decimal("75"), decimal("72"),
			"좋은 연습", List.of(), "karaoke-v3", notes, curve
		);

		service.save(7L, 50L, request);

		var stored = ArgumentCaptor.forClass(String.class);
		verify(resultRepository).save(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(50L),
			org.mockito.ArgumentMatchers.eq("COMPLETED"), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), stored.capture());
		var payload = new ObjectMapper().readValue(stored.getValue(), PracticeResultService.SummaryPayload.class);
		assertThat(payload.dataVersion()).isEqualTo("karaoke-v3");
		assertThat(payload.notes()).containsExactlyElementsOf(notes);
		assertThat(payload.curve()).isEqualTo(curve);
	}

	@Test
	void returnsStoredDetailedDeviceJudgementsAndPitchCurve() throws Exception {
		var notes = List.of(new PracticeResultRequest.NoteEvaluation(3, "GOOD", 12, 0.08));
		var curve = new PracticeResultRequest.PitchCurve(100, 0, java.util.Arrays.asList(60.25, null, 61.0));
		String stored = new ObjectMapper().writeValueAsString(
			new PracticeResultService.SummaryPayload("좋은 연습", List.of(), "karaoke-v3", notes, curve)
		);
		when(practiceRepository.findOwned(50L, 7L)).thenReturn(Optional.of(practice("COMPLETED")));
		when(resultRepository.findLatest(50L)).thenReturn(Optional.of(
			new ResultRow(60L, 50L, "COMPLETED", decimal("70"), decimal("65"), decimal("75"), decimal("72"),
				stored, LocalDateTime.now(), LocalDateTime.now())
		));

		var report = service.report(7L, 50L);

		assertThat(report.dataVersion()).isEqualTo("karaoke-v3");
		assertThat(report.notes()).containsExactlyElementsOf(notes);
		assertThat(report.curve()).isEqualTo(curve);
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
