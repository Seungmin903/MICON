package com.micon.backend.domain.recommendation.service;

import java.time.LocalDateTime;

public record RecommendationDispatchResult(
        long recommendationId, String jobId, String status, LocalDateTime generatedAt) {
}
