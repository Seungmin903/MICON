package com.micon.backend.practice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.practice.api.PracticeRequest;
import com.micon.backend.practice.api.PracticeRequest.PracticeScope;
import com.micon.backend.practice.api.PracticeResponse;
import com.micon.backend.practice.repository.PracticeRepository;
import com.micon.backend.practice.repository.PracticeRepository.SongRow;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeService {

	private final PracticeRepository repository;

	public PracticeService(PracticeRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public PracticeResponse.Detail create(Long userId, PracticeRequest.Create request) {
		SongRow song = repository.findPracticeReadySong(request.songId())
			.orElseThrow(() -> new BusinessException(ErrorCode.SONG_NOT_FOUND));
		if (!song.practiceReady()) {
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}
		if (!repository.isUploadedRecordingOwnedBy(request.recordingId(), userId)) {
			throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
		}

		PracticeScope scope = request.practiceScope() == null ? PracticeScope.FULL_SONG : request.practiceScope();
		validateRange(request, scope, song.durationMs());
		int selectedKey = request.selectedKey() == null ? 0 : request.selectedKey();
		if (!repository.hasReadyInstrumental(request.songId(), selectedKey)) {
			throw new BusinessException(selectedKey == 0
				? ErrorCode.PRACTICE_ASSET_NOT_READY
				: ErrorCode.UNSUPPORTED_PRACTICE_KEY);
		}
		if (scope == PracticeScope.SECTION
			&& !repository.sectionBelongsToSong(request.sectionId(), request.songId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "선택한 구간이 해당 곡에 속하지 않습니다.");
		}

		Long practiceId = nextIdentifier();
		repository.create(
			practiceId,
			userId,
			request.songId(),
			scope == PracticeScope.SECTION ? request.sectionId() : null,
			request.recordingId(),
			selectedKey,
			request.playbackSpeed() == null ? new BigDecimal("1.00") : request.playbackSpeed(),
			scope == PracticeScope.CUSTOM ? request.customStartMs() : null,
			scope == PracticeScope.CUSTOM ? request.customEndMs() : null,
			scope
		);
		return get(userId, practiceId);
	}

	@Transactional(readOnly = true)
	public PracticeResponse.Detail get(Long userId, Long practiceId) {
		return repository.findOwned(practiceId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_NOT_FOUND));
	}

	@Transactional
	public PracticeResponse.Detail complete(Long userId, Long practiceId, PracticeRequest.Complete request) {
		requireOwned(userId, practiceId);
		if (repository.complete(practiceId, userId, request.durationMs(), request.voiceAnalysisId()) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return get(userId, practiceId);
	}

	@Transactional
	public PracticeResponse.Detail cancel(Long userId, Long practiceId) {
		requireOwned(userId, practiceId);
		if (repository.cancel(practiceId, userId) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return get(userId, practiceId);
	}

	private void validateRange(PracticeRequest.Create request, PracticeScope scope, Long songDurationMs) {
		if (scope == PracticeScope.SECTION && request.sectionId() == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "구간 연습에는 sectionId가 필요합니다.");
		}
		if (scope != PracticeScope.SECTION && request.sectionId() != null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "구간 연습에서만 sectionId를 지정할 수 있습니다.");
		}
		if (scope == PracticeScope.CUSTOM) {
			if (request.customStartMs() == null || request.customEndMs() == null
				|| request.customStartMs() >= request.customEndMs()
				|| (songDurationMs != null && request.customEndMs() > songDurationMs)) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "사용자 지정 연습 범위가 올바르지 않습니다.");
			}
		} else if (request.customStartMs() != null || request.customEndMs() != null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "사용자 지정 연습에서만 시작·종료 시간을 지정할 수 있습니다.");
		}
	}

	private void requireOwned(Long userId, Long practiceId) {
		if (repository.findOwned(practiceId, userId).isEmpty()) {
			throw new BusinessException(ErrorCode.PRACTICE_NOT_FOUND);
		}
	}

	private Long nextIdentifier() {
		Long identifier;
		do {
			identifier = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
		} while (repository.identifierExists(identifier));
		return identifier;
	}
}
