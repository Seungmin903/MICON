package com.micon.backend.domain.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record VoiceAnalysisComponentCallbackRequest(
        @NotBlank String requestId,
        @NotBlank String jobId,
        String component,
        @NotNull Status status,
        String modelKey,
        String modelVersion,
        Integer dimension,
        List<Double> embedding,
        JsonNode segmentInfo,
        JsonNode qualityMetrics,
        String failureCode,
        String failureReason,
        @NotNull LocalDateTime processedAt,
        @NotNull Integer attemptNo,
        String measurementId,
        String protocolVersion,
        BigDecimal lowMidi,
        BigDecimal highMidi,
        BigDecimal stableLowMidi,
        BigDecimal stableHighMidi,
        JsonNode noteResults,
        String measurementStatus,
        String qualityStatus,
        Long resultVersion,
        String catalogVersion,
        List<Artist> artists
) {
    public VoiceAnalysisComponentCallbackRequest(String requestId, String jobId, String component,
            Status status, String modelKey, String modelVersion, Integer dimension, List<Double> embedding,
            JsonNode segmentInfo, JsonNode qualityMetrics, String failureCode, String failureReason,
            LocalDateTime processedAt, Integer attemptNo, String measurementId, String protocolVersion,
            BigDecimal lowMidi, BigDecimal highMidi, JsonNode noteResults, String measurementStatus,
            String qualityStatus, Long resultVersion, String catalogVersion, List<Artist> artists) {
        this(requestId, jobId, component, status, modelKey, modelVersion, dimension, embedding,
                segmentInfo, qualityMetrics, failureCode, failureReason, processedAt, attemptNo,
                measurementId, protocolVersion, lowMidi, highMidi, null, null, noteResults,
                measurementStatus, qualityStatus, resultVersion, catalogVersion, artists);
    }
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        INSUFFICIENT_DATA
    }

    public record Artist(
            @NotNull Long artistId,
            @NotNull Integer rank,
            @NotNull Double similarity,
            Double ecapaScore,
            Double singerByolScore
    ) { }
}
