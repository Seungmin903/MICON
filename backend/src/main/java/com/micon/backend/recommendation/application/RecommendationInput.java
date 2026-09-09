package com.micon.backend.recommendation.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RecommendationInput(
        Long voiceAnalysisId,
        Source source,
        VoiceProfile voiceProfile,
        List<String> genres,
        List<String> preferredArtists
) {
    public RecommendationInput {
        Objects.requireNonNull(voiceAnalysisId, "voiceAnalysisId is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(voiceProfile, "voiceProfile is required");
        genres = genres == null ? List.of() : List.copyOf(genres);
        preferredArtists = preferredArtists == null ? List.of() : List.copyOf(preferredArtists);
    }

    public interface Source {
    }

    public record RecordingSource(Long recordingId, String storagePath, String mediaType) implements Source {
        public RecordingSource {
            if (recordingId == null || storagePath == null || storagePath.isBlank()
                    || mediaType == null || mediaType.isBlank()) {
                throw new IllegalArgumentException("recording source is incomplete");
            }
        }
    }

    public record VoiceProfile(
            BigDecimal stableLowMidi,
            BigDecimal stableHighMidi,
            BigDecimal medianMidi,
            String pitchDistribution,
            BigDecimal rangeConfidence,
            String timbreLabel,
            String timbreVector,
            BigDecimal timbreConfidence,
            String userEmbedding,
            String embeddingRef
    ) {
    }
}
