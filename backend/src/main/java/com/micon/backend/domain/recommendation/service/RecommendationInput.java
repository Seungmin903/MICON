package com.micon.backend.domain.recommendation.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RecommendationInput(
        Long profileId,
        Long sourceSessionId,
        long profileVersion,
        long preferenceVersion,
        String sourceSongId,
        VoiceProfile voiceProfile,
        List<String> genres,
        List<String> preferredArtists
) {
    public RecommendationInput(Long profileId, Long sourceSessionId, long profileVersion,
                               String sourceSongId, VoiceProfile voiceProfile,
                               List<String> genres, List<String> preferredArtists) {
        this(profileId, sourceSessionId, profileVersion, 0L, sourceSongId, voiceProfile, genres, preferredArtists);
    }

    public RecommendationInput {
        Objects.requireNonNull(profileId, "profileId is required");
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
