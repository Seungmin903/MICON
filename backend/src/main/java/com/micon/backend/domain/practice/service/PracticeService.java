package com.micon.backend.domain.practice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.practice.repository.PracticeRepository;
import com.micon.backend.domain.practice.dto.request.PracticeRequest;
import com.micon.backend.domain.practice.dto.request.PracticeRequest.PracticeScope;
import com.micon.backend.domain.practice.dto.response.PracticeResponse;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 연습 가능 여부와 소유권을 검증하고 연습 세션 생성·상태 변경·완료·취소를 처리한다. */
@Service
public class PracticeService {

	private final PracticeRepository repository;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeService 객체를 생성한다.
	public PracticeService(PracticeRepository repository) {
		this.repository = repository;
	}

	@Transactional
	// 기능: create 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
	public PracticeResponse.Detail create(Long userId, PracticeRequest.Create request) {
		PracticeRepository.SongRow song = repository.findPracticeReadySong(request.songId())
			.orElseThrow(() -> new BusinessException(ErrorCode.SONG_NOT_FOUND));
		if (!song.practiceReady()) {
			throw new BusinessException(ErrorCode.PRACTICE_ASSET_NOT_READY);
		}
		if (request.recordingId() != null
			&& !repository.isUploadedRecordingOwnedBy(request.recordingId(), userId)) {
			throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
		}

		PracticeScope scope = request.practiceScope() == null ? PracticeScope.FULL_SONG : request.practiceScope();
		validateRange(request, scope, song.durationMs());
		int selectedKey = request.selectedKey() == null ? 0 : request.selectedKey();
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
	// 기능: get 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PracticeResponse.Detail get(Long userId, Long practiceId) {
		return repository.findOwned(practiceId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_NOT_FOUND));
	}

	@Transactional
	// 기능: delete 권한과 상태를 확인한 뒤 대상을 삭제하거나 취소한다.
	public void delete(Long userId, Long practiceId) {
		requireOwned(userId, practiceId);
		if (repository.delete(practiceId, userId) == 0) {
			throw new BusinessException(ErrorCode.PRACTICE_NOT_FOUND);
		}
	}

	@Transactional
	// 기능: complete 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
	public PracticeResponse.Detail complete(Long userId, Long practiceId, PracticeRequest.Complete request) {
		PracticeResponse.Detail practice = requireOwned(userId, practiceId);
		if (request.recordingId() != null
			&& !repository.isUploadedRecordingOwnedBy(request.recordingId(), userId)) {
			throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
		}
		if (request.keyChangeCount() != null && request.keyChangeCount() == 0
			&& request.finalSelectedKey() != null
			&& request.finalSelectedKey() != practice.initialKey()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "최종 키가 최초 키와 다르면 키 변경 횟수는 1 이상이어야 합니다.");
		}
		if (repository.complete(
			practiceId, userId, request.durationMs(), request.voiceAnalysisId(), request.recordingId(),
			request.finalSelectedKey(), request.keyChangeCount()
		) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return get(userId, practiceId);
	}

	@Transactional
	// 기능: updateKey 업무 규칙을 검증하고 현재 선택 키와 변경 횟수를 저장한다.
	public PracticeResponse.Detail updateKey(
		Long userId,
		Long practiceId,
		PracticeRequest.KeyUpdate request
	) {
		requireOwned(userId, practiceId);
		if (repository.updateSelectedKey(practiceId, userId, request.selectedKey()) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return get(userId, practiceId);
	}

	@Transactional
	// 기능: cancel 권한과 상태를 확인한 뒤 대상을 삭제하거나 취소한다.
	public PracticeResponse.Detail cancel(Long userId, Long practiceId) {
		requireOwned(userId, practiceId);
		if (repository.cancel(practiceId, userId) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return get(userId, practiceId);
	}

	@Transactional
	// 기능: updateStatus 업무 규칙을 검증하고 상태를 변경한다.
	public PracticeResponse.Detail updateStatus(
		Long userId,
		Long practiceId,
		PracticeRequest.StatusUpdate request
	) {
		requireOwned(userId, practiceId);
		if (repository.updateStatus(practiceId, userId, request.status().name()) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return get(userId, practiceId);
	}

	// 기능: validateRange 처리에 필요한 업무 조건을 검증한다.
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

	// 기능: requireOwned 비즈니스 로직을 수행한다.
	private PracticeResponse.Detail requireOwned(Long userId, Long practiceId) {
		return repository.findOwned(practiceId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_NOT_FOUND));
	}

	// 기능: nextIdentifier 저장에 사용할 새 식별자를 생성한다.
	private Long nextIdentifier() {
		Long identifier;
		do {
			identifier = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
		} while (repository.identifierExists(identifier));
		return identifier;
	}
}
