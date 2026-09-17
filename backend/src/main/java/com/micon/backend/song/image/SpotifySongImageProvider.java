package com.micon.backend.song.image;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Spotify 곡 검색 결과에서 제목과 가수가 일치하는 트랙의 앨범 커버를 찾는다. */
@Component
public class SpotifySongImageProvider {

	private static final Logger log = LoggerFactory.getLogger(SpotifySongImageProvider.class);

	private final SpotifyImageProperties properties;
	private final SpotifyAccessTokenProvider tokenProvider;
	private final RestClient spotifyClient;

	public SpotifySongImageProvider(
		SpotifyImageProperties properties,
		SpotifyAccessTokenProvider tokenProvider
	) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.spotifyClient = RestClient.builder()
			.baseUrl(properties.apiBaseUrl())
			.requestFactory(requestFactory)
			.build();
	}

	/** 곡명과 대표 가수로 검색하고 정확히 일치하는 트랙의 가장 큰 이미지를 반환한다. */
	public Optional<SpotifyTrackImage> findImage(String title, String artist) {
		if (!properties.available() || isBlank(title) || isBlank(artist)) {
			return Optional.empty();
		}
		Optional<String> token = tokenProvider.getAccessToken();
		if (token.isEmpty()) {
			return Optional.empty();
		}
		try {
			return search(title, artist, token.get());
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
				tokenProvider.invalidate();
				return tokenProvider.getAccessToken()
					.flatMap(refreshed -> safelySearch(title, artist, refreshed));
			}
			logFailure(title, artist, exception);
			return Optional.empty();
		} catch (RestClientException exception) {
			logFailure(title, artist, exception);
			return Optional.empty();
		}
	}

	/** Spotify 트랙 ID가 있으면 제목 번역이나 표기 차이와 무관하게 해당 트랙 이미지를 조회한다. */
	public Optional<SpotifyTrackImage> findImageByTrackId(String trackId) {
		if (!properties.available() || isBlank(trackId)) {
			return Optional.empty();
		}
		Optional<String> token = tokenProvider.getAccessToken();
		if (token.isEmpty()) {
			return Optional.empty();
		}
		try {
			return getTrack(trackId, token.get());
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
				tokenProvider.invalidate();
				return tokenProvider.getAccessToken()
					.flatMap(refreshed -> safelyGetTrack(trackId, refreshed));
			}
			logFailure(trackId, "track-id", exception);
			return Optional.empty();
		} catch (RestClientException exception) {
			logFailure(trackId, "track-id", exception);
			return Optional.empty();
		}
	}

	private Optional<SpotifyTrackImage> safelySearch(String title, String artist, String token) {
		try {
			return search(title, artist, token);
		} catch (RestClientException exception) {
			logFailure(title, artist, exception);
			return Optional.empty();
		}
	}

	private Optional<SpotifyTrackImage> safelyGetTrack(String trackId, String token) {
		try {
			return getTrack(trackId, token);
		} catch (RestClientException exception) {
			logFailure(trackId, "track-id", exception);
			return Optional.empty();
		}
	}

	private Optional<SpotifyTrackImage> getTrack(String trackId, String token) {
		JsonNode track = spotifyClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/v1/tracks/{trackId}")
				.queryParam("market", properties.market())
				.build(trackId.trim()))
			.headers(headers -> headers.setBearerAuth(token))
			.retrieve()
			.body(JsonNode.class);
		return toTrackImage(track);
	}

	private Optional<SpotifyTrackImage> search(String title, String artist, String token) {
		JsonNode response = spotifyClient.get()
			.uri(uriBuilder -> uriBuilder
				.path("/v1/search")
				.queryParam("q", "track:" + title + " artist:" + artist)
				.queryParam("type", "track")
				.queryParam("market", properties.market())
				.queryParam("limit", 5)
				.build())
			.headers(headers -> headers.setBearerAuth(token))
			.retrieve()
			.body(JsonNode.class);
		return findMatchingTrack(response, title, artist);
	}

	private Optional<SpotifyTrackImage> findMatchingTrack(JsonNode response, String title, String artist) {
		if (response == null) {
			return Optional.empty();
		}
		JsonNode items = response.path("tracks").path("items");
		if (!items.isArray()) {
			return Optional.empty();
		}
		String expectedTitle = normalize(title);
		String expectedArtist = normalize(artist);
		for (JsonNode item : items) {
			if (!expectedTitle.equals(normalize(item.path("name").asText()))
				|| !containsArtist(item.path("artists"), expectedArtist)) {
				continue;
			}
			return toTrackImage(item);
		}
		return Optional.empty();
	}

	private Optional<SpotifyTrackImage> toTrackImage(JsonNode track) {
		if (track == null) {
			return Optional.empty();
		}
		String imageUrl = largestImageUrl(track.path("album").path("images")).orElse(null);
		String sourceUrl = track.path("external_urls").path("spotify").asText().trim();
		if (isBlank(imageUrl) || isBlank(sourceUrl)) {
			return Optional.empty();
		}
		return Optional.of(new SpotifyTrackImage(imageUrl, sourceUrl));
	}

	private boolean containsArtist(JsonNode artists, String expectedArtist) {
		if (!artists.isArray()) {
			return false;
		}
		for (JsonNode artist : artists) {
			if (expectedArtist.equals(normalize(artist.path("name").asText()))) {
				return true;
			}
		}
		return false;
	}

	private Optional<String> largestImageUrl(JsonNode images) {
		if (!images.isArray()) {
			return Optional.empty();
		}
		return java.util.stream.StreamSupport.stream(images.spliterator(), false)
			.filter(image -> !isBlank(image.path("url").asText()))
			.max(Comparator.comparingLong(this::imageArea))
			.map(image -> image.path("url").asText().trim());
	}

	private long imageArea(JsonNode image) {
		return (long) image.path("width").asInt(0) * image.path("height").asInt(0);
	}

	private String normalize(String value) {
		return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
			.trim()
			.toLowerCase()
			.replaceAll("\\s+", " ");
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private void logFailure(String title, String artist, Exception exception) {
		log.warn("Spotify 곡 이미지 조회에 실패했습니다. title={}, artist={}, error={}",
			title, artist, exception.getClass().getSimpleName());
	}
}
