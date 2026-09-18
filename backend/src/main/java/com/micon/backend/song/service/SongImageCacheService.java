package com.micon.backend.song.service;

import com.micon.backend.song.image.SpotifyTrackImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Spotify 커버를 약관에 맞게 제한된 시간 동안만 서버 메모리에 캐시한다. */
@Service
public class SongImageCacheService {

	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Instant> misses = new ConcurrentHashMap<>();
	private final Duration ttl;
	private final Duration missTtl;
	private final int maxEntries;

	public SongImageCacheService(com.micon.backend.song.image.SpotifyImageProperties properties) {
		this.ttl = properties.cacheTtl();
		this.missTtl = properties.missCacheTtl();
		this.maxEntries = properties.cacheMaxEntries();
	}

	/** 만료되지 않은 임시 이미지가 있으면 반환한다. */
	public Optional<SpotifyTrackImage> find(String songId) {
		CacheEntry entry = cache.get(songId);
		if (entry == null) {
			return Optional.empty();
		}
		if (!entry.expiresAt().isAfter(Instant.now())) {
			cache.remove(songId, entry);
			return Optional.empty();
		}
		return Optional.of(entry.image());
	}

	/** 최근 조회에서 찾지 못한 곡이면 짧은 시간 동안 재호출을 막는다. */
	public boolean isKnownMissing(String songId) {
		Instant expiresAt = misses.get(songId);
		if (expiresAt == null) {
			return false;
		}
		if (!expiresAt.isAfter(Instant.now())) {
			misses.remove(songId, expiresAt);
			return false;
		}
		return true;
	}

	/** 캐시 크기를 제한하면서 새 Spotify 이미지를 임시 보관한다. */
	public void cache(String songId, SpotifyTrackImage image) {
		Instant now = Instant.now();
		removeExpired(now);
		if (cache.size() >= maxEntries && !cache.containsKey(songId)) {
			cache.entrySet().stream()
				.min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
				.ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
		}
		misses.remove(songId);
		cache.put(songId, new CacheEntry(image, now.plus(ttl)));
	}

	/** 검색 결과 없음이나 일시 오류를 짧게 캐시해 반복 호출과 429 위험을 줄인다. */
	public void cacheMiss(String songId) {
		Instant now = Instant.now();
		removeExpired(now);
		if (misses.size() >= maxEntries && !misses.containsKey(songId)) {
			misses.entrySet().stream()
				.min(java.util.Map.Entry.comparingByValue())
				.ifPresent(entry -> misses.remove(entry.getKey(), entry.getValue()));
		}
		misses.put(songId, now.plus(missTtl));
	}

	private void removeExpired(Instant now) {
		cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
		misses.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
	}

	private record CacheEntry(SpotifyTrackImage image, Instant expiresAt) {
	}
}
