package com.micon.backend.song.service;

import com.micon.backend.song.api.PageResponse;
import com.micon.backend.song.api.SongResponse;
import com.micon.backend.song.repository.SongQueryRepository;
import com.micon.backend.song.repository.SongQueryRepository.SongDetailRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 곡 검색·상세 조회 유스케이스를 실행하고 조회 결과를 API 응답 형태로 조립한다. */
@Service
@Transactional(readOnly = true)
public class SongService {

	private final SongQueryRepository songQueryRepository;

	// 기능: 필요한 의존성과 초기값을 받아 SongService 객체를 생성한다.
	public SongService(SongQueryRepository songQueryRepository) {
		this.songQueryRepository = songQueryRepository;
	}

	// 기능: search 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PageResponse<SongResponse.Summary> search(String query, int page, int size) {
		long offset = (long) page * size;
		List<SongResponse.Summary> songs = songQueryRepository.findPage(query, size, offset);
		long totalElements = songQueryRepository.count(query);
		return PageResponse.of(songs, page, size, totalElements);
	}

	// 기능: getDetail 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
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
