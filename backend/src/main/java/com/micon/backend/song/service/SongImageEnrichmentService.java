package com.micon.backend.song.service;

import com.micon.backend.song.api.SongResponse;
import com.micon.backend.song.image.SpotifyImageProperties;
import com.micon.backend.song.image.SpotifySongImageProvider;
import com.micon.backend.song.image.SpotifyTrackImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 이미지가 없는 곡만 Spotify에서 보완하고 응답과 임시 캐시에 반영한다. */
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

	/** 목록 중 이미지가 비어 있는 곡을 설정된 최대 호출 수 안에서 보완한다. */
	public List<SongResponse.Summary> enrich(List<SongResponse.Summary> songs) {
		if (!properties.available()) {
			return songs;
		}
		int remainingLookups = properties.maxLookupsPerRequest();
		List<SongResponse.Summary> enriched = new ArrayList<>(songs.size());
		for (SongResponse.Summary song : songs) {
			if (hasText(song.imageUrl()) || song.artists().isEmpty()) {
				enriched.add(song);
				continue;
			}
			SpotifyTrackImage cached = cacheService.find(song.songId()).orElse(null);
			if (cached != null) {
				enriched.add(withImage(song, cached));
				continue;
			}
			if (cacheService.isKnownMissing(song.songId())) {
				enriched.add(song);
				continue;
			}
			if (remainingLookups == 0) {
				enriched.add(song);
				continue;
			}
			remainingLookups--;
			SpotifyTrackImage image = imageProvider.findImage(song.title(), song.artists().getFirst()).orElse(null);
			if (image == null) {
				cacheService.cacheMiss(song.songId());
				enriched.add(song);
				continue;
			}
			cacheService.cache(song.songId(), image);
			enriched.add(withImage(song, image));
		}
		return List.copyOf(enriched);
	}

	/** 상세 조회 한 건의 이미지가 비어 있을 때 Spotify 결과로 보완한다. */
	public ImageResult enrich(
		String songId,
		String title,
		List<String> artists,
		String imageUrl,
		String imageSourceUrl
	) {
		if (hasText(imageUrl) || artists.isEmpty() || !properties.available()) {
			return new ImageResult(imageUrl, imageSourceUrl);
		}
		Optional<SpotifyTrackImage> cached = cacheService.find(songId);
		if (cached.isPresent()) {
			return toResult(cached.get());
		}
		if (cacheService.isKnownMissing(songId)) {
			return new ImageResult(null, imageSourceUrl);
		}
		return imageProvider.findImage(title, artists.getFirst())
			.map(image -> {
				cacheService.cache(songId, image);
				return toResult(image);
			})
			.orElseGet(() -> {
				cacheService.cacheMiss(songId);
				return new ImageResult(null, imageSourceUrl);
			});
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
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
