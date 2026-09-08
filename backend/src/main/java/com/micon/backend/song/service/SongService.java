package com.micon.backend.song.service;

import com.micon.backend.song.api.PageResponse;
import com.micon.backend.song.api.SongResponse;
import com.micon.backend.song.repository.SongQueryRepository;
import com.micon.backend.song.repository.SongQueryRepository.SongDetailRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SongService {

	private final SongQueryRepository songQueryRepository;

	public SongService(SongQueryRepository songQueryRepository) {
		this.songQueryRepository = songQueryRepository;
	}

	public PageResponse<SongResponse.Summary> search(String query, int page, int size) {
		long offset = (long) page * size;
		List<SongResponse.Summary> songs = songQueryRepository.findPage(query, size, offset);
		long totalElements = songQueryRepository.count(query);
		return PageResponse.of(songs, page, size, totalElements);
	}

	public SongResponse.Detail getDetail(Long songId, Long userId) {
		SongDetailRow song = songQueryRepository.findDetail(songId)
				.orElseThrow(() -> new SongNotFoundException(songId));
		return new SongResponse.Detail(
			song.songId(),
			song.title(),
			songQueryRepository.findArtists(songId),
			song.albumName(),
			song.imageUrl(),
			song.releaseDate(),
			song.durationMs(),
			song.originalKey(),
			song.bpm(),
			songQueryRepository.findGenres(songId),
			song.lowestMidi(),
			song.highestMidi(),
			songQueryRepository.isFavorite(songId, userId),
			Boolean.TRUE.equals(song.practiceReady())
		);
	}
}
