package com.micon.backend.voice.api;

import tools.jackson.databind.JsonNode;
import com.micon.backend.voice.domain.VoiceQualityStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VoiceAnalysisCallbackRequest(
        @NotNull Long voiceAnalysisId, @NotBlank String jobId, @NotNull CallbackStatus status,
        String analysisVersion, VoiceQualityStatus qualityStatus, String failureCode, String failureReason,
        @Valid RangeResult range, @Valid TimbreResult timbre, @NotNull LocalDateTime measuredAt) {
    public enum CallbackStatus { COMPLETED, FAILED, INSUFFICIENT_DATA }
    public record RangeResult(BigDecimal fullLowMidi, BigDecimal fullHighMidi, BigDecimal stableLowMidi,
            BigDecimal stableHighMidi, BigDecimal pitchMedianMidi, BigDecimal pitchAccuracyScore,
            BigDecimal pitchStabilityScore, JsonNode pitchDistribution, BigDecimal modalLowMidi,
            BigDecimal modalHighMidi, BigDecimal falsettoLowMidi, BigDecimal falsettoHighMidi,
            BigDecimal highNoteHoldSec, BigDecimal confidence, JsonNode noteResults) {}
    public record TimbreResult(String timbreLabel, JsonNode timbreVector, BigDecimal rhythmScore,
            BigDecimal pronunciationScore, BigDecimal confidence, String explanation,
            BigDecimal validSingleSec, Integer validChunkCount, JsonNode userEmbedding, String embeddingRef,
            BigDecimal chunkCohesion, BigDecimal observedCoreLowMidi, BigDecimal observedCoreHighMidi,
            BigDecimal observedMedianMidi, JsonNode pitchHistogram) {}
}
