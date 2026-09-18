package com.micon.backend.domain.voice.client;

import com.micon.backend.common.observability.ObservedAiRequestFactory;

import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpArtistSearchJobGateway implements ArtistSearchJobGateway {

    private static final Set<String> REQUIRED_MODELS = Set.of("ECAPA", "SINGER_BYOL");

    private final RestClient restClient;
    private final boolean serviceTokenConfigured;

    public HttpArtistSearchJobGateway(
            @Value("${ai.recommendation.base-url:http://localhost:8788}") String baseUrl,
            @Value("${AI_SERVICE_TOKEN:}") String serviceToken,
            @Value("${ai.voice-analysis-connect-timeout:3s}") Duration connectTimeout,
            @Value("${ai.voice-analysis-read-timeout:30s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new ObservedAiRequestFactory(requestFactory));
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.restClient = builder.build();
        this.serviceTokenConfigured = serviceToken != null && !serviceToken.isBlank();
    }

    @Override
    public Result submit(Command command) {
        validate(command);
        if (!serviceTokenConfigured) {
            throw new Rejected("AI service token is required for artist search");
        }
        var response = restClient.post()
                .uri("/artist-search-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .toEntity(Result.class);
        Result result = response.getBody();
        if (response.getStatusCode() != HttpStatus.ACCEPTED
                || result == null
                || result.jobId() == null
                || result.jobId().isBlank()
                || !"QUEUED".equals(result.status())
                || result.audioExpiresAt() != null) {
            throw new Rejected("AI must return 202 QUEUED without audioExpiresAt for artist search");
        }
        return result;
    }

    @Override
    public Result retry(String jobId) {
        var response = restClient.post().uri("/artist-search-jobs/{jobId}/retry", jobId)
                .retrieve().toEntity(Result.class);
        if (response.getStatusCode() != org.springframework.http.HttpStatus.ACCEPTED
                || response.getBody() == null) {
            throw new ArtistSearchJobGateway.Rejected("AI did not accept the artist search retry");
        }
        return response.getBody();
    }

    private void validate(Command command) {
        if (command == null
                || command.requestId() == null || command.requestId().isBlank()
                || !uuid(command.requestId())
                || command.analysisId() < 1
                || command.sessionId() < 1
                || !"ARTIST_SEARCH".equals(command.analysisType())
                || !List.of("ARTIST_SEARCH").equals(command.components())
                || command.attemptNo() < 1
                || command.resultVersion() < 1
                || command.topK() < 1
                || command.modelResults() == null
                || !command.modelResults().keySet().equals(REQUIRED_MODELS)
                || invalidModel(command.modelResults().get("ECAPA"), 192)
                || invalidModel(command.modelResults().get("SINGER_BYOL"), 1000)
                || blank(command.catalogVersion())
                || blank(command.scoringVersion())
                || blank(command.callbackUrl())
                || !httpUrl(command.callbackUrl())
                || blank(command.contractVersion())) {
            throw new IllegalArgumentException("artist search request does not match the BE-AI contract");
        }
    }

    private boolean invalidModel(ModelResult result, int expectedDimension) {
        return result == null
                || blank(result.modelVersion())
                || result.dimension() != expectedDimension
                || result.embedding() == null
                || result.embedding().size() != expectedDimension
                || result.embedding().stream().anyMatch(value -> value == null || !Double.isFinite(value));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean uuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean httpUrl(String value) {
        try {
            String scheme = URI.create(value).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
