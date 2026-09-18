package com.micon.backend.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.practice.api.PracticeRequest;
import com.micon.backend.domain.practice.api.PracticeRequest.PracticeScope;
import com.micon.backend.domain.practice.api.PracticeResponse;
import com.micon.backend.domain.practice.repository.PracticeRepository;
import com.micon.backend.domain.practice.repository.PracticeRepository.SongRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import com.micon.backend.domain.practice.service.PracticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PracticeServiceTest {

	@Mock
	private PracticeRepository repository;

	private PracticeService service;

	@BeforeEach
	void setUp() {
		service = new PracticeService(repository);
	}

	@Test
	void createsSectionPracticeForOwnedUploadedRecording() {
		var request = new PracticeRequest.Create(
			"10", 20L, 30L, -2, new BigDecimal("0.80"), PracticeScope.SECTION, null, null
		);
		var detail = detail("IN_PROGRESS");
		when(repository.findPracticeReadySong("10")).thenReturn(Optional.of(new SongRow("10", 180_000L, true)));
		when(repository.isUploadedRecordingOwnedBy(20L, 7L)).thenReturn(true);
		when(repository.hasReadyInstrumental("10", -2)).thenReturn(true);
		when(repository.sectionBelongsToSong(30L, "10")).thenReturn(true);
		when(repository.findOwned(anyLong(), eq(7L))).thenReturn(Optional.of(detail));

		assertThat(service.create(7L, request)).isSameAs(detail);
		verify(repository).create(
			anyLong(), eq(7L), eq("10"), eq(30L), eq(20L), eq(-2),
			eq(new BigDecimal("0.80")), eq(null), eq(null), eq(PracticeScope.SECTION)
		);
	}

	@Test
	void rejectsInvalidCustomRange() {
		var request = new PracticeRequest.Create(
			"10", 20L, null, 0, null, PracticeScope.CUSTOM, 90_000, 200_000
		);
		when(repository.findPracticeReadySong("10")).thenReturn(Optional.of(new SongRow("10", 180_000L, true)));
		when(repository.isUploadedRecordingOwnedBy(20L, 7L)).thenReturn(true);

		assertThatThrownBy(() -> service.create(7L, request))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
	}

	@Test
	void rejectsCompletingTerminalSession() {
		when(repository.findOwned(50L, 7L)).thenReturn(Optional.of(detail("ABORTED")));
		when(repository.complete(50L, 7L, 12_000, null, null)).thenReturn(0);

		assertThatThrownBy(() -> service.complete(7L, 50L, new PracticeRequest.Complete(12_000, null, null)))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRACTICE_STATE));
	}

	@Test
	void hidesAnotherUsersPractice() {
		when(repository.findOwned(50L, 7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(7L, 50L))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_NOT_FOUND));
	}

	@Test
	void deletesOwnedPracticeReport() {
		when(repository.findOwned(50L, 7L)).thenReturn(Optional.of(detail("COMPLETED")));
		when(repository.delete(50L, 7L)).thenReturn(1);

		service.delete(7L, 50L);

		verify(repository).delete(50L, 7L);
	}

	@Test
	void rejectsDeletingAnotherUsersPracticeReport() {
		when(repository.findOwned(50L, 7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.delete(7L, 50L))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_NOT_FOUND));
		verify(repository, never()).delete(50L, 7L);
	}

	@Test
	void updatesActivePracticeStatus() {
		var recording = detail("RECORDING");
		when(repository.findOwned(50L, 7L)).thenReturn(Optional.of(detail("READY")), Optional.of(recording));
		when(repository.updateStatus(50L, 7L, "RECORDING")).thenReturn(1);

		assertThat(service.updateStatus(
			7L, 50L, new PracticeRequest.StatusUpdate(PracticeRequest.ActiveStatus.RECORDING)
		)).isSameAs(recording);
	}

	private PracticeResponse.Detail detail(String status) {
		return new PracticeResponse.Detail(
			50L, "10", "연습곡", 30L, 20L, null, -2, new BigDecimal("0.80"),
			null, null, "SECTION", status, LocalDateTime.now(), null, null, LocalDateTime.now()
		);
	}
}
