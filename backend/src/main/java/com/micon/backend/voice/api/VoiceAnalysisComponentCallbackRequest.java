package com.micon.backend.voice.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record VoiceAnalysisComponentCallbackRequest(
        @NotBlank String requestId,
        @NotBlank String jobId,
        @NotNull Status status,
        String modelKey,
        String modelVersion,
        Integer dimension,
        List<Double> embedding,
        JsonNode segmentInfo,
        JsonNode qualityMetrics,
        String failureCode,
        String failureReason,
        @NotNull LocalDateTime processedAt
) {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        INSUFFICIENT_DATA
    }
}
