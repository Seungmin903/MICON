package com.micon.backend.recommendation.application;

import java.time.LocalDateTime;

public record RecommendationDispatchResult(
        long recommendationId, String jobId, String status, LocalDateTime generatedAt) {
}
