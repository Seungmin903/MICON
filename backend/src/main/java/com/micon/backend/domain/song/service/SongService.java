package com.micon.backend.domain.song.service;

import com.micon.backend.domain.song.repository.SongQueryRepository;
import com.micon.backend.domain.song.dto.PageResponse;
import com.micon.backend.domain.song.dto.SongResponse;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 곡 검색·상세 조회 유스케이스를 실행하고 조회 결과를 API 응답 형태로 조립한다. */
@Service
@Transactional(readOnly = true)
public class SongService {

	private final SongQueryRepository songQueryRepository;
	private final SongImageEnrichmentService imageEnrichmentService;

	// 기능: 필요한 의존성과 초기값을 받아 SongService 객체를 생성한다.
	public SongService(
		SongQueryRepository songQueryRepository,
		SongImageEnrichmentService imageEnrichmentService
	) {
		this.songQueryRepository = songQueryRepository;
		this.imageEnrichmentService = imageEnrichmentService;
	}

	// 기능: search 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PageResponse<SongResponse.Summary> search(String query, int page, int size) {
		return search(query, page, size, null);
	}

	/** 로그인 회원은 저장한 선호 장르와 많이 일치하는 곡부터 조회한다. */
	public PageResponse<SongResponse.Summary> search(String query, int page, int size, Long userId) {
		long offset = (long) page * size;
		List<SongResponse.Summary> songsWithoutImages = songQueryRepository.findPage(query, size, offset, userId);
		List<SongResponse.Summary> songs = imageEnrichmentService.enrich(
			songsWithoutImages,
			songQueryRepository.findTrackIds(
				songsWithoutImages.stream().map(SongResponse.Summary::songId).toList()
			)
		);
		long totalElements = songQueryRepository.count(query);
		return PageResponse.of(songs, page, size, totalElements);
	}

	// 기능: getDetail 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public SongResponse.Detail getDetail(String songId, Long userId) {
		SongQueryRepository.SongDetailRow song = songQueryRepository.findDetail(songId)
				.orElseThrow(() -> new SongNotFoundException(songId));
		List<String> artists = songQueryRepository.findArtists(songId);
		SongImageEnrichmentService.ImageResult image = imageEnrichmentService.enrich(
			songId, song.trackId(), song.title(), artists
		);
		return new SongResponse.Detail(
			song.songId(),
			song.title(),
			artists,
			song.albumName(),
			image.imageUrl(),
			image.imageSourceUrl(),
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
