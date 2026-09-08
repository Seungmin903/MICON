package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.*;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VoiceAnalysisReportResponse(
        VoiceAnalysisHistoryResponse.Summary analysis, Range range, Timbre timbre) {
    public record Range(BigDecimal fullLowMidi, BigDecimal fullHighMidi, BigDecimal stableLowMidi,
            BigDecimal stableHighMidi, BigDecimal pitchMedianMidi, BigDecimal pitchAccuracyScore,
            BigDecimal pitchStabilityScore, JsonNode pitchDistribution, BigDecimal modalLowMidi,
            BigDecimal modalHighMidi, BigDecimal falsettoLowMidi, BigDecimal falsettoHighMidi,
            BigDecimal highNoteHoldSec, BigDecimal confidence, JsonNode noteResults, LocalDateTime measuredAt) {}
    public record Timbre(String timbreLabel, BigDecimal rhythmScore, BigDecimal pronunciationScore,
            BigDecimal confidence, String explanation, BigDecimal validSingleSec, Integer validChunkCount,
            BigDecimal chunkCohesion, BigDecimal observedCoreLowMidi, BigDecimal observedCoreHighMidi,
            BigDecimal observedMedianMidi, JsonNode pitchHistogram, LocalDateTime measuredAt) {}
}
