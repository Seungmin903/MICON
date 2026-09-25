package com.micon.backend.domain.setlist.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.setlist.repository.SetlistRepository;
import com.micon.backend.domain.song.service.SongNotFoundException;
import com.micon.backend.domain.setlist.dto.SetlistResponse;
import com.micon.backend.domain.setlist.exception.SetlistNotFoundException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 소유권과 곡 유효성을 검증하며 세트리스트 생성·수정·곡 순서 관리 유스케이스를 처리한다. */
@Service
public class SetlistService {

	private final SetlistRepository setlistRepository;

	// 기능: 필요한 의존성과 초기값을 받아 SetlistService 객체를 생성한다.
	public SetlistService(SetlistRepository setlistRepository) {
		this.setlistRepository = setlistRepository;
	}

	@Transactional
	// 기능: create 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
	public SetlistResponse.Summary create(long userId, String name) {
		return toSummary(setlistRepository.create(userId, name.trim()), 0);
	}

	@Transactional(readOnly = true)
	// 기능: getSetlists 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public List<SetlistResponse.Summary> getSetlists(long userId) {
		return setlistRepository.findAll(userId).stream()
			.map(row -> new SetlistResponse.Summary(
				row.setlistId(), row.name(), row.songCount(), row.coverImageUrl(), row.createdAt(), row.updatedAt()
			))
			.toList();
	}

	@Transactional(readOnly = true)
	// 기능: getSetlist 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public SetlistResponse.Detail getSetlist(long userId, Long setlistId) {
		return detail(requireOwned(userId, setlistId));
	}

	@Transactional
	// 기능: rename 업무 규칙을 검증하고 상태를 변경한다.
	public SetlistResponse.Summary rename(long userId, Long setlistId, String name) {
		requireOwnedForUpdate(userId, setlistId);
		SetlistRepository.SetlistRow renamed = setlistRepository.rename(setlistId, userId, name.trim());
		return toSummary(renamed, setlistRepository.findSongIds(setlistId).size());
	}

	@Transactional
	// 기능: delete 권한과 상태를 확인한 뒤 대상을 삭제하거나 취소한다.
	public void delete(long userId, Long setlistId) {
		requireOwnedForUpdate(userId, setlistId);
		setlistRepository.delete(setlistId, userId);
	}

	@Transactional
	// 기능: addSong 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
	public SetlistResponse.Detail addSong(long userId, Long setlistId, String songId) {
		SetlistRepository.SetlistRow setlist = requireOwnedForUpdate(userId, setlistId);
		if (!setlistRepository.songExists(songId)) {
			throw new SongNotFoundException(songId);
		}
		setlistRepository.addSong(setlistId, songId);
		return detail(setlistRepository.findOwned(setlist.setlistId(), userId).orElseThrow());
	}

	@Transactional
	// 기능: removeSong 권한과 상태를 확인한 뒤 대상을 삭제하거나 취소한다.
	public SetlistResponse.Detail removeSong(long userId, Long setlistId, String songId) {
		requireOwnedForUpdate(userId, setlistId);
		setlistRepository.removeSong(setlistId, songId);
		return detail(setlistRepository.findOwned(setlistId, userId).orElseThrow());
	}

	@Transactional
	// 기능: reorderSongs 업무 규칙을 검증하고 상태를 변경한다.
	public SetlistResponse.Detail reorderSongs(
		long userId,
		Long setlistId,
		List<String> songIds,
		LocalDateTime expectedUpdatedAt
	) {
		SetlistRepository.SetlistRow setlist = requireOwnedForUpdate(userId, setlistId);
		if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(setlist.updatedAt())) {
			throw new BusinessException(ErrorCode.SETLIST_CONFLICT);
		}
		List<String> currentSongIds = setlistRepository.findSongIds(setlistId);
		if (songIds.size() != currentSongIds.size()
			|| new HashSet<>(songIds).size() != songIds.size()
			|| !new HashSet<>(songIds).equals(new HashSet<>(currentSongIds))) {
			throw new BusinessException(ErrorCode.INVALID_SETLIST_ORDER);
		}
		setlistRepository.reorderSongs(setlistId, songIds);
		return detail(setlistRepository.findOwned(setlistId, userId).orElseThrow());
	}

	// 기능: requireOwned 비즈니스 로직을 수행한다.
	private SetlistRepository.SetlistRow requireOwned(long userId, Long setlistId) {
		return setlistRepository.findOwned(setlistId, userId)
			.orElseThrow(() -> new SetlistNotFoundException(setlistId));
	}

	// 기능: requireOwnedForUpdate 비즈니스 로직을 수행한다.
	private SetlistRepository.SetlistRow requireOwnedForUpdate(long userId, Long setlistId) {
		return setlistRepository.findOwnedForUpdate(setlistId, userId)
			.orElseThrow(() -> new SetlistNotFoundException(setlistId));
	}

	// 기능: detail 비즈니스 로직을 수행한다.
	private SetlistResponse.Detail detail(SetlistRepository.SetlistRow row) {
		return new SetlistResponse.Detail(
			row.setlistId(), row.name(), setlistRepository.findSongs(row.setlistId()),
			row.createdAt(), row.updatedAt()
		);
	}

	// 기능: toSummary 원본 값을 API 응답 또는 내부 데이터 형태로 변환한다.
	private SetlistResponse.Summary toSummary(SetlistRepository.SetlistRow row, int songCount) {
		return new SetlistResponse.Summary(
			row.setlistId(), row.name(), songCount, null, row.createdAt(), row.updatedAt()
		);
	}
}
