package com.micon.backend.recommendation.api;

import com.micon.backend.recommendation.application.RecommendationDispatchResult;
import com.micon.backend.recommendation.domain.RecommendationStatus;

import java.time.LocalDateTime;
import java.util.List;

public record RecommendationCreateResponse(
        long recommendationId,
        String jobId,
        RecommendationStatus status,
        int progress,
        LocalDateTime generatedAt,
        List<Object> shelves
) {
    public static RecommendationCreateResponse from(RecommendationDispatchResult result) {
        return new RecommendationCreateResponse(
                result.recommendationId(), result.jobId(), RecommendationStatus.GENERATING,
                0, result.generatedAt(), List.of());
    }
}
