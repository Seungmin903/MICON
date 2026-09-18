package com.micon.backend.domain.recommendation.application;

import java.time.LocalDateTime;

public record RecommendationDispatchResult(
        long recommendationId, String jobId, String status, LocalDateTime generatedAt) {
}
