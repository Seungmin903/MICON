package com.micon.backend.voice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpVoiceAnalysisJobGateway implements VoiceAnalysisJobGateway {
    private final RestClient restClient;

    public HttpVoiceAnalysisJobGateway(
            @Value("${ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service-token:}") String serviceToken) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.restClient = builder.build();
    }

    @Override
    public Result submit(Command command) {
        return restClient.post().uri("/voice-analysis-jobs")
                .body(command).retrieve().body(Result.class);
    }

    @Override
    public void cancel(String jobId) {
        restClient.post().uri("/voice-analysis-jobs/{jobId}/cancel", jobId)
                .retrieve().toBodilessEntity();
    }
}
