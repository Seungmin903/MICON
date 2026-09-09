package com.micon.backend.setlist.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.setlist.api.SetlistResponse;
import com.micon.backend.setlist.repository.SetlistRepository;
import com.micon.backend.setlist.repository.SetlistRepository.SetlistRow;
import com.micon.backend.song.service.SongNotFoundException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetlistService {

	private final SetlistRepository setlistRepository;

	public SetlistService(SetlistRepository setlistRepository) {
		this.setlistRepository = setlistRepository;
	}

	@Transactional
	public SetlistResponse.Summary create(long userId, String name) {
		return toSummary(setlistRepository.create(userId, name.trim()), 0);
	}

	@Transactional(readOnly = true)
	public List<SetlistResponse.Summary> getSetlists(long userId) {
		return setlistRepository.findAll(userId).stream()
			.map(row -> new SetlistResponse.Summary(
				row.setlistId(), row.name(), row.songCount(), row.createdAt(), row.updatedAt()
			))
			.toList();
	}

	@Transactional(readOnly = true)
	public SetlistResponse.Detail getSetlist(long userId, Long setlistId) {
		return detail(requireOwned(userId, setlistId));
	}

	@Transactional
	public SetlistResponse.Summary rename(long userId, Long setlistId, String name) {
		requireOwnedForUpdate(userId, setlistId);
		SetlistRow renamed = setlistRepository.rename(setlistId, userId, name.trim());
		return toSummary(renamed, setlistRepository.findSongIds(setlistId).size());
	}

	@Transactional
	public void delete(long userId, Long setlistId) {
		requireOwnedForUpdate(userId, setlistId);
		setlistRepository.delete(setlistId, userId);
	}

	@Transactional
	public SetlistResponse.Detail addSong(long userId, Long setlistId, Long songId) {
		SetlistRow setlist = requireOwnedForUpdate(userId, setlistId);
		if (!setlistRepository.songExists(songId)) {
			throw new SongNotFoundException(songId);
		}
		if (!setlistRepository.isFavorite(userId, songId)) {
			throw new BusinessException(ErrorCode.SONG_NOT_FAVORITED);
		}
		setlistRepository.addSong(setlistId, songId);
		return detail(setlistRepository.findOwned(setlist.setlistId(), userId).orElseThrow());
	}

	@Transactional
	public SetlistResponse.Detail removeSong(long userId, Long setlistId, Long songId) {
		requireOwnedForUpdate(userId, setlistId);
		setlistRepository.removeSong(setlistId, songId);
		return detail(setlistRepository.findOwned(setlistId, userId).orElseThrow());
	}

	@Transactional
	public SetlistResponse.Detail reorderSongs(
		long userId,
		Long setlistId,
		List<Long> songIds,
		LocalDateTime expectedUpdatedAt
	) {
		SetlistRow setlist = requireOwnedForUpdate(userId, setlistId);
		if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(setlist.updatedAt())) {
			throw new BusinessException(ErrorCode.SETLIST_CONFLICT);
		}
		List<Long> currentSongIds = setlistRepository.findSongIds(setlistId);
		if (songIds.size() != currentSongIds.size()
			|| new HashSet<>(songIds).size() != songIds.size()
			|| !new HashSet<>(songIds).equals(new HashSet<>(currentSongIds))) {
			throw new BusinessException(ErrorCode.INVALID_SETLIST_ORDER);
		}
		setlistRepository.reorderSongs(setlistId, songIds);
		return detail(setlistRepository.findOwned(setlistId, userId).orElseThrow());
	}

	private SetlistRow requireOwned(long userId, Long setlistId) {
		return setlistRepository.findOwned(setlistId, userId)
			.orElseThrow(() -> new SetlistNotFoundException(setlistId));
	}

	private SetlistRow requireOwnedForUpdate(long userId, Long setlistId) {
		return setlistRepository.findOwnedForUpdate(setlistId, userId)
			.orElseThrow(() -> new SetlistNotFoundException(setlistId));
	}

	private SetlistResponse.Detail detail(SetlistRow row) {
		return new SetlistResponse.Detail(
			row.setlistId(), row.name(), setlistRepository.findSongs(row.setlistId()),
			row.createdAt(), row.updatedAt()
		);
	}

	private SetlistResponse.Summary toSummary(SetlistRow row, int songCount) {
		return new SetlistResponse.Summary(
			row.setlistId(), row.name(), songCount, row.createdAt(), row.updatedAt()
		);
	}
}
