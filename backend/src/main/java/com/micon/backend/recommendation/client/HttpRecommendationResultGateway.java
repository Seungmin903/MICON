package com.micon.backend.recommendation.client;

import com.micon.backend.common.observability.ObservedAiRequestFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.micon.backend.recommendation.config.RecommendationAiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Component
public class HttpRecommendationResultGateway implements RecommendationResultGateway {
    private final RestClient restClient;

    public HttpRecommendationResultGateway(RecommendationAiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(new ObservedAiRequestFactory(requestFactory));
        if (!properties.serviceToken().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken());
        }
        this.restClient = builder.build();
    }

    @Override
    public Job fetch(String jobId) {
        AiJob response = restClient.get().uri("/recommendations/{jobId}", jobId).retrieve().body(AiJob.class);
        if (response == null) {
            throw new IllegalStateException("AI recommendation job response is empty");
        }
        return new Job(response.id(), Status.valueOf(response.status().toUpperCase(Locale.ROOT)),
                response.progress(), response.message(), result(response.result()), response.error());
    }

    private Result result(AiResult source) {
        if (source == null) return null;
        Algorithm algorithm = source.algorithm() == null ? null
                : new Algorithm(source.algorithm().scoringVersion(), source.algorithm().shelvesVersion());
        List<Shelf> shelves = source.shelves() == null ? List.of() : source.shelves().stream()
                .map(shelf -> new Shelf(shelf.key(), shelf.title(), shelf.metric(), shelf.items() == null ? List.of() :
                        shelf.items().stream().map(this::item).toList()))
                .toList();
        return new Result(algorithm, shelves);
    }

    private Item item(AiItem item) {
        return new Item(item.position(), item.songId(), item.axis(), item.label(), item.timbreScore(),
                item.rangeFit(), item.recommendedKey(), item.finalScore(), item.metrics(),
                item.scoringVersion(), item.compositionVersion());
    }

    private record AiJob(String id, String status, int progress, String message, AiResult result, String error) {}
    private record AiResult(AiAlgorithm algorithm, List<AiShelf> shelves) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record AiAlgorithm(String scoringVersion, String shelvesVersion) {}
    private record AiShelf(String key, String title, String metric, List<AiItem> items) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record AiItem(int position, Long songId, String axis, String label, BigDecimal timbreScore,
                          JsonNode rangeFit, Short recommendedKey, BigDecimal finalScore, JsonNode metrics,
                          String scoringVersion, String compositionVersion) {}
}
