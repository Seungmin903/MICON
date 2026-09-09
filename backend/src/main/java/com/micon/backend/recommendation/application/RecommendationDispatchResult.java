package com.micon.backend.recommendation.application;

public record RecommendationDispatchResult(long recommendationId, String jobId, String status) {
}
