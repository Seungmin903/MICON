package com.micon.backend.domain.song.service;

import com.micon.backend.domain.song.image.SpotifySongImageProvider;
import com.micon.backend.domain.song.image.SpotifyTrackImage;
import com.micon.backend.domain.song.api.SongResponse;
import com.micon.backend.domain.song.image.SpotifyImageProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** DB 이미지와 무관하게 Spotify 커버만 조회해 응답과 임시 캐시에 반영한다. */
@Service
public class SongImageEnrichmentService {

	private final SpotifySongImageProvider imageProvider;
	private final SpotifyImageProperties properties;
	private final SongImageCacheService cacheService;

	public SongImageEnrichmentService(
		SpotifySongImageProvider imageProvider,
		SpotifyImageProperties properties,
		SongImageCacheService cacheService
	) {
		this.imageProvider = imageProvider;
		this.properties = properties;
		this.cacheService = cacheService;
	}

	/** 목록의 기존 DB 이미지를 제거하고 Spotify 커버만 설정된 호출 수 안에서 조회한다. */
	public List<SongResponse.Summary> enrich(List<SongResponse.Summary> songs) {
		return enrich(songs, Map.of());
	}

	/** Spotify 트랙 ID가 있으면 검색 대신 직접 조회해 제목 번역 차이로 인한 누락을 막는다. */
	public List<SongResponse.Summary> enrich(
		List<SongResponse.Summary> songs,
		Map<String, String> trackIds
	) {
		int remainingLookups = properties.maxLookupsPerRequest();
		List<SongResponse.Summary> enriched = new ArrayList<>(songs.size());
		for (SongResponse.Summary song : songs) {
			SongResponse.Summary spotifyOnlySong = withoutImage(song);
			if (song.artists().isEmpty() || !properties.available()) {
				enriched.add(spotifyOnlySong);
				continue;
			}
			SpotifyTrackImage cached = cacheService.find(song.songId()).orElse(null);
			if (cached != null) {
				enriched.add(withImage(song, cached));
				continue;
			}
			if (cacheService.isKnownMissing(song.songId())) {
				enriched.add(spotifyOnlySong);
				continue;
			}
			if (remainingLookups == 0) {
				enriched.add(spotifyOnlySong);
				continue;
			}
			remainingLookups--;
			SpotifyTrackImage image = findImage(
				trackIds.get(song.songId()), song.title(), song.artists().getFirst()
			).orElse(null);
			if (image == null) {
				cacheService.cacheMiss(song.songId());
				enriched.add(spotifyOnlySong);
				continue;
			}
			cacheService.cache(song.songId(), image);
			enriched.add(withImage(song, image));
		}
		return List.copyOf(enriched);
	}

	/** 상세 조회의 DB 이미지는 사용하지 않고 Spotify 커버만 반환한다. */
	public ImageResult enrich(
		String songId,
		String title,
		List<String> artists
	) {
		return enrich(songId, null, title, artists);
	}

	/** 상세 조회에서도 저장된 Spotify 트랙 ID를 우선 사용한다. */
	public ImageResult enrich(
		String songId,
		String trackId,
		String title,
		List<String> artists
	) {
		if (artists.isEmpty() || !properties.available()) {
			return new ImageResult(null, null);
		}
		Optional<SpotifyTrackImage> cached = cacheService.find(songId);
		if (cached.isPresent()) {
			return toResult(cached.get());
		}
		if (cacheService.isKnownMissing(songId)) {
			return new ImageResult(null, null);
		}
		return findImage(trackId, title, artists.getFirst())
			.map(image -> {
				cacheService.cache(songId, image);
				return toResult(image);
			})
			.orElseGet(() -> {
				cacheService.cacheMiss(songId);
				return new ImageResult(null, null);
			});
	}

	private Optional<SpotifyTrackImage> findImage(String trackId, String title, String artist) {
		if (trackId != null && !trackId.isBlank()) {
			return imageProvider.findImageByTrackId(trackId);
		}
		return imageProvider.findImage(title, artist);
	}

	private SongResponse.Summary withoutImage(SongResponse.Summary song) {
		return new SongResponse.Summary(
			song.songId(), song.title(), song.artists(), null, null, song.durationMs()
		);
	}

	private SongResponse.Summary withImage(SongResponse.Summary song, SpotifyTrackImage image) {
		return new SongResponse.Summary(
			song.songId(), song.title(), song.artists(), image.imageUrl(), image.sourceUrl(), song.durationMs()
		);
	}

	private ImageResult toResult(SpotifyTrackImage image) {
		return new ImageResult(image.imageUrl(), image.sourceUrl());
	}

	public record ImageResult(String imageUrl, String imageSourceUrl) {
	}
}
