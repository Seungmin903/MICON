package com.micon.backend.songfit.client;

import com.micon.backend.songfit.api.SongFitResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpSongFitGateway implements SongFitGateway {

	private final RestClient restClient;

	public HttpSongFitGateway(
		@Value("${ai.recommendation.base-url:http://localhost:8788}") String baseUrl,
		@Value("${ai.service-token:}") String serviceToken
	) {
		RestClient.Builder configured = RestClient.builder().baseUrl(baseUrl);
		if (serviceToken != null && !serviceToken.isBlank()) {
			configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
		}
		this.restClient = configured.build();
	}

	@Override
	public SongFitResponse evaluate(Command command) {
		return restClient.post()
			.uri("/song-fit")
			.body(command)
			.retrieve()
			.body(SongFitResponse.class);
	}
}
