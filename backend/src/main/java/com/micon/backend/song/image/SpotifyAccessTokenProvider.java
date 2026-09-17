package com.micon.backend.song.image;

import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Client Credentials 방식으로 Spotify 앱 토큰을 발급받아 만료 전까지 재사용한다. */
@Component
public class SpotifyAccessTokenProvider {

	private static final Logger log = LoggerFactory.getLogger(SpotifyAccessTokenProvider.class);
	private static final long EXPIRY_SAFETY_SECONDS = 30;

	private final SpotifyImageProperties properties;
	private final RestClient accountsClient;
	private volatile CachedToken cachedToken;

	public SpotifyAccessTokenProvider(SpotifyImageProperties properties) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.accountsClient = RestClient.builder()
			.baseUrl(properties.accountsBaseUrl())
			.requestFactory(requestFactory)
			.build();
	}

	/** 유효한 토큰을 반환하고, 없거나 만료되었으면 한 번 새로 발급한다. */
	public Optional<String> getAccessToken() {
		if (!properties.available()) {
			return Optional.empty();
		}
		CachedToken current = cachedToken;
		if (current != null && current.validAt(Instant.now())) {
			return Optional.of(current.value());
		}
		return refreshAccessToken();
	}

	/** Spotify가 토큰을 거부한 경우 다음 요청에서 재발급하도록 캐시를 비운다. */
	public void invalidate() {
		cachedToken = null;
	}

	private synchronized Optional<String> refreshAccessToken() {
		CachedToken current = cachedToken;
		if (current != null && current.validAt(Instant.now())) {
			return Optional.of(current.value());
		}
		try {
			JsonNode response = accountsClient.post()
				.uri("/api/token")
				.headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body("grant_type=client_credentials")
				.retrieve()
				.body(JsonNode.class);
			if (response == null || response.path("access_token").asText().isBlank()) {
				return Optional.empty();
			}
			String value = response.path("access_token").asText();
			long expiresIn = Math.max(60, response.path("expires_in").asLong(3600));
			cachedToken = new CachedToken(
				value,
				Instant.now().plusSeconds(Math.max(1, expiresIn - EXPIRY_SAFETY_SECONDS))
			);
			return Optional.of(value);
		} catch (RestClientException exception) {
			log.warn("Spotify access token 발급에 실패했습니다. error={}",
				exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private record CachedToken(String value, Instant expiresAt) {
		private boolean validAt(Instant now) {
			return now.isBefore(expiresAt);
		}
	}
}
