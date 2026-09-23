package com.micon.backend.domain.recommendation.dto;

import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.song.dto.SongResponse;
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
        List<Shelf> shelves,
        Long profileId,
        Long sourceSessionId,
        Long profileVersion,
        Long preferenceVersion,
        Long currentPreferenceVersion
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
            String compositionVersion,
            SongResponse.Summary summary
    ) {
        public Song(String songId, int overallRank, int position, String axis, String label, Short recommendedKey,
                    BigDecimal finalScore, BigDecimal timbreScore, JsonNode rangeFit, JsonNode metrics,
                    String reasonText, String scoringVersion, String compositionVersion) {
            this(songId, overallRank, position, axis, label, recommendedKey, finalScore, timbreScore, rangeFit,
                    metrics, reasonText, scoringVersion, compositionVersion, null);
        }
    }
}
