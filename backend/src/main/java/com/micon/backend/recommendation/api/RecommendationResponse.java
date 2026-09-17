package com.micon.backend.recommendation.api;

import com.micon.backend.recommendation.domain.RecommendationStatus;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RecommendationResponse(
        Long recommendationId,
        String jobId,
        RecommendationStatus status,
        int progress,
        String failureCode,
        String failureReason,
        String algorithmVersion,
        String scoringVersion,
        String compositionVersion,
        LocalDateTime generatedAt,
        LocalDateTime completedAt,
        LocalDateTime expiresAt,
        List<Shelf> shelves
) {
    public record Shelf(String key, String title, String metric, int order, List<Song> items) {}

    public record Song(
            String songId,
            int overallRank,
            int position,
            String axis,
            String label,
            Short recommendedKey,
            BigDecimal finalScore,
            BigDecimal timbreScore,
            JsonNode rangeFit,
            JsonNode metrics,
            String reasonText,
            String scoringVersion,
            String compositionVersion
    ) {}
}
