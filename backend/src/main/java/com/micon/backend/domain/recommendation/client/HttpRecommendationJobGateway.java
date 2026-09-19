package com.micon.backend.domain.recommendation.client;

import com.micon.backend.common.observability.ObservedAiRequestFactory;

import com.micon.backend.domain.recommendation.service.RecommendationInput;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.recommendation.config.RecommendationAiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

@Component
public class HttpRecommendationJobGateway implements RecommendationJobGateway {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpRecommendationJobGateway(RecommendationAiProperties properties, ObjectMapper objectMapper) {
        this.restClient = createClient(properties);
        this.objectMapper = objectMapper;
    }

    @Override
    public Result submit(Command command) {
        AiJobResponse response = restClient.post()
                .uri("/recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request(command))
                .retrieve()
                .body(AiJobResponse.class);
        return response == null ? null : new Result(response.id(), response.status());
    }

    private AiRecommendationRequest request(Command command) {
        RecommendationInput input = command.input();
        RecommendationInput.VoiceProfile profile = input.voiceProfile();
        return new AiRecommendationRequest(
                input.profileId(), input.sourceSongId(), command.topK(), aiMode(command.mode()),
                input.genres(), input.preferredArtists(),
                new AiVoiceProfile(
                        profile.stableLowMidi(), profile.stableHighMidi(), profile.medianMidi(),
                        json(profile.pitchDistribution()), profile.rangeConfidence(), profile.timbreLabel(),
                        json(profile.timbreVector()), profile.timbreConfidence(), profile.observedCoreLowMidi(),
                        profile.observedCoreHighMidi(), profile.observedMedianMidi(), json(profile.pitchHistogram())),
                requiredJson(profile.userEmbedding(), "userEmbedding"), profile.embeddingRef());
    }

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("recommendation input contains invalid JSON", exception);
        }
    }

    private JsonNode requiredJson(String value, String field) {
        JsonNode parsed = json(value);
        if (parsed == null || parsed.isNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return parsed;
    }

    private String aiMode(RecommendationMode mode) {
        return mode == RecommendationMode.RANGE ? "range" : "timbre";
    }

    private static RestClient createClient(RecommendationAiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new ObservedAiRequestFactory(requestFactory));
        if (!properties.serviceToken().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken());
        }
        return builder.build();
    }

    private record AiRecommendationRequest(
            Long profileId, String sourceSongId, int topK, String mode,
            List<String> genres, List<String> preferredArtists,
            AiVoiceProfile voiceProfile, JsonNode userEmbedding, String embeddingRef
    ) {}

    private record AiVoiceProfile(
            BigDecimal stableLowMidi, BigDecimal stableHighMidi, BigDecimal medianMidi,
            JsonNode pitchDistribution, BigDecimal rangeConfidence, String timbreLabel,
            JsonNode timbreVector, BigDecimal timbreConfidence,
            BigDecimal observedCoreLowMidi, BigDecimal observedCoreHighMidi,
            BigDecimal observedMedianMidi, JsonNode pitchHistogram
    ) {}

    private record AiJobResponse(String id, String status) {}
}
