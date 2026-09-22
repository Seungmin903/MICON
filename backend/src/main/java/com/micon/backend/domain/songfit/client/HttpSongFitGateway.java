package com.micon.backend.domain.songfit.client;

import com.micon.backend.common.observability.ObservedAiRequestFactory;
import com.micon.backend.common.observability.AccessLog;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import com.micon.backend.domain.songfit.dto.SongFitResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class HttpSongFitGateway implements SongFitGateway {

	private final RestClient restClient;

	public HttpSongFitGateway(
		@Value("${ai.recommendation.base-url:http://localhost:8788}") String baseUrl,
		@Value("${ai.service-token:}") String serviceToken
	) {
		RestClient.Builder configured = RestClient.builder().baseUrl(baseUrl)
			.requestFactory(new ObservedAiRequestFactory(new JdkClientHttpRequestFactory()));
		if (serviceToken != null && !serviceToken.isBlank()) {
			configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
		}
		this.restClient = configured.build();
	}

	@Override
	public SongFitResponse evaluate(Command command) {
		try {
			return restClient.post()
				.uri("/song-fit")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(command)
				.retrieve()
				.body(SongFitResponse.class);
		} catch (RestClientResponseException exception) {
			logRejectedResponse(exception);
			throw exception;
		}
	}

	/** Logs only protocol diagnostics, never the command payload or a raw AI response body. */
	private void logRejectedResponse(RestClientResponseException exception) {
		String response = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
		HttpHeaders headers = exception.getResponseHeaders();
		AccessLog.event("ai_song_fit_rejected", true,
			"status", exception.getStatusCode().value(),
			"response_content_type", headers == null ? "-" : headers.getContentType(),
			"response_bytes", response.getBytes(StandardCharsets.UTF_8).length,
			"diagnostic", diagnostic(response));
	}

	private String diagnostic(String response) {
		if (response == null || response.isBlank()) return "empty_response";
		String normalized = response.toLowerCase(Locale.ROOT);
		if (normalized.contains("error parsing the body")) return "json_parse_error";
		if (normalized.contains("field required") || normalized.contains("field_required")) return "json_body_missing";
		return "detail_sha256_" + AccessLog.fingerprint(response);
	}
}
