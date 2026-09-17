package com.micon.backend.recommendation.client;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

public interface RecommendationResultGateway {
    Job fetch(String jobId);

    enum Status { QUEUED, PROCESSING, COMPLETE, FAILED }

    record Job(String id, Status status, int progress, String message, Result result, String error) {}

    record Result(Algorithm algorithm, List<Shelf> shelves) {}

    record Algorithm(String scoringVersion, String shelvesVersion) {}

    record Shelf(String key, String title, String metric, List<Item> items) {}

    record Item(int position, String songId, String axis, String label, BigDecimal timbreScore,
                JsonNode rangeFit, Short recommendedKey, BigDecimal finalScore, JsonNode metrics,
                String scoringVersion, String compositionVersion) {}
}
