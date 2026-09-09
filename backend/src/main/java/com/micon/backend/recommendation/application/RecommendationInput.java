package com.micon.backend.recommendation.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RecommendationInput(
        Long voiceAnalysisId,
        Long sourceSongId,
        VoiceProfile voiceProfile,
        List<String> genres,
        List<String> preferredArtists
) {
    public RecommendationInput {
        Objects.requireNonNull(voiceAnalysisId, "voiceAnalysisId is required");
        Objects.requireNonNull(voiceProfile, "voiceProfile is required");
        genres = genres == null ? List.of() : List.copyOf(genres);
        preferredArtists = preferredArtists == null ? List.of() : List.copyOf(preferredArtists);
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
            String embeddingRef,
            BigDecimal observedCoreLowMidi,
            BigDecimal observedCoreHighMidi,
            BigDecimal observedMedianMidi,
            String pitchHistogram
    ) {
    }
}
