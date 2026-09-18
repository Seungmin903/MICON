package com.micon.backend.domain.song.image;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spotify 곡 이미지 조회에 필요한 서버 인증과 호출 제한 설정을 제공한다. */
@ConfigurationProperties(prefix = "song.image.spotify")
public record SpotifyImageProperties(
	boolean enabled,
	String accountsBaseUrl,
	String apiBaseUrl,
	String clientId,
	String clientSecret,
	String market,
	int maxLookupsPerRequest,
	Duration cacheTtl,
	Duration missCacheTtl,
	int cacheMaxEntries,
	Duration connectTimeout,
	Duration readTimeout
) {
	public SpotifyImageProperties {
		accountsBaseUrl = defaultValue(accountsBaseUrl, "https://accounts.spotify.com");
		apiBaseUrl = defaultValue(apiBaseUrl, "https://api.spotify.com");
		clientId = trim(clientId);
		clientSecret = trim(clientSecret);
		market = defaultValue(market, "KR").toUpperCase();
		maxLookupsPerRequest = maxLookupsPerRequest < 1 ? 20 : maxLookupsPerRequest;
		cacheTtl = defaultDuration(cacheTtl, Duration.ofHours(6));
		missCacheTtl = defaultDuration(missCacheTtl, Duration.ofMinutes(10));
		cacheMaxEntries = cacheMaxEntries < 1 ? 5000 : cacheMaxEntries;
		connectTimeout = defaultDuration(connectTimeout, Duration.ofSeconds(2));
		readTimeout = defaultDuration(readTimeout, Duration.ofSeconds(3));
	}

	public boolean available() {
		return enabled && !clientId.isBlank() && !clientSecret.isBlank();
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private static String defaultValue(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static Duration defaultDuration(Duration value, Duration fallback) {
		return value == null || value.isNegative() || value.isZero() ? fallback : value;
	}
}
