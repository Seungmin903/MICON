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

	// 기능: 필요한 의존성과 초기값을 받아 SongService 객체를 생성한다.
	public SongService(SongQueryRepository songQueryRepository) {
		this.songQueryRepository = songQueryRepository;
	}

	// 기능: search 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public PageResponse<SongResponse.Summary> search(String query, int page, int size) {
		return search(query, page, size, null);
	}

	/** 로그인 회원은 저장한 선호 장르와 많이 일치하는 곡부터 조회한다. */
	public PageResponse<SongResponse.Summary> search(String query, int page, int size, Long userId) {
		return search(query, page, size, userId, null);
	}

	/** 장르 필터가 있으면 해당 장르에 연결된 곡만 조회한다. */
	public PageResponse<SongResponse.Summary> search(String query, int page, int size, Long userId, Long genreId) {
		return search(query, page, size, userId, genreId, null);
	}

	public PageResponse<SongResponse.Summary> search(String query, int page, int size, Long userId, Long genreId, Long artistId) {
		long offset = (long) page * size;
		boolean catalog = (query == null || query.trim().isEmpty()) && genreId == null && artistId == null;
		boolean genreCatalog = (query == null || query.trim().isEmpty()) && genreId != null && artistId == null;
		List<SongResponse.Summary> songs = catalog ? songQueryRepository.findCatalogPage(size, offset, userId)
				: genreCatalog ? songQueryRepository.findGenrePage(genreId, size, offset, userId)
				: songQueryRepository.findPage(query, size, offset, userId, genreId, artistId);
		long totalElements = catalog ? songQueryRepository.countCatalog()
				: genreCatalog ? songQueryRepository.countGenre(genreId)
				: songQueryRepository.count(query, genreId, artistId);
		return PageResponse.of(songs, page, size, totalElements);
	}

	// 기능: getDetail 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public SongResponse.Detail getDetail(String songId, Long userId) {
		SongQueryRepository.SongDetailRow song = songQueryRepository.findDetail(songId)
				.orElseThrow(() -> new SongNotFoundException(songId));
		List<String> artists = songQueryRepository.findArtists(songId);
		return new SongResponse.Detail(
			song.songId(),
			song.title(),
			artists,
			song.albumName(),
			song.imageUrl(),
			null,
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
