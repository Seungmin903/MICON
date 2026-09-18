package com.micon.backend.favorite.service;

import com.micon.backend.favorite.api.FavoriteResponse;
import com.micon.backend.favorite.repository.FavoriteRepository;
import com.micon.backend.song.service.SongNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 곡 존재 여부를 검증한 뒤 회원의 찜 추가·삭제·목록 조회를 처리한다. */
@Service
public class FavoriteService {

	private final FavoriteRepository favoriteRepository;

	// 기능: 필요한 의존성과 초기값을 받아 FavoriteService 객체를 생성한다.
	public FavoriteService(FavoriteRepository favoriteRepository) {
		this.favoriteRepository = favoriteRepository;
	}

	@Transactional
	// 기능: add 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
	public void add(long userId, String songId) {
		validateSong(songId);
		favoriteRepository.add(userId, songId);
	}

	@Transactional
	// 기능: remove 권한과 상태를 확인한 뒤 대상을 삭제하거나 취소한다.
	public void remove(long userId, String songId) {
		validateSong(songId);
		favoriteRepository.remove(userId, songId);
	}

	@Transactional(readOnly = true)
	// 기능: getFavorites 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public FavoriteResponse getFavorites(long userId) {
		var songs = favoriteRepository.findAll(userId);
		return new FavoriteResponse(songs, songs.size());
	}

	// 기능: validateSong 처리에 필요한 업무 조건을 검증한다.
	private void validateSong(String songId) {
		if (!favoriteRepository.songExists(songId)) {
			throw new SongNotFoundException(songId);
		}
	}
}
