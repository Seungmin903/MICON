package com.micon.backend.favorite.service;

import com.micon.backend.favorite.api.FavoriteResponse;
import com.micon.backend.favorite.repository.FavoriteRepository;
import com.micon.backend.song.service.SongNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {

	private final FavoriteRepository favoriteRepository;

	public FavoriteService(FavoriteRepository favoriteRepository) {
		this.favoriteRepository = favoriteRepository;
	}

	@Transactional
	public void add(long userId, Long songId) {
		validateSong(songId);
		favoriteRepository.add(userId, songId);
	}

	@Transactional
	public void remove(long userId, Long songId) {
		validateSong(songId);
		favoriteRepository.remove(userId, songId);
	}

	@Transactional(readOnly = true)
	public FavoriteResponse getFavorites(long userId) {
		var songs = favoriteRepository.findAll(userId);
		return new FavoriteResponse(songs, songs.size());
	}

	private void validateSong(Long songId) {
		if (!favoriteRepository.songExists(songId)) {
			throw new SongNotFoundException(songId);
		}
	}
}
