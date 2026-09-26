package com.micon.backend.domain.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

public final class VoiceSessionRequest {
    private VoiceSessionRequest() {}
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RangeAnalysisUpload(
            @Schema(description = "앱이 생성한 멱등 요청 UUID")
            @NotBlank @Size(max = 100) String requestId,
            @Min(1) int attemptNo,
            @NotBlank @Size(max = 100) String measurementId,
            @NotNull LocalDateTime measuredAt,
            @NotBlank String protocolVersion,
            @NotBlank @Pattern(regexp = "basic|extension") String mode,
            Long parentAnalysisId,
            @Min(36) @Max(84) int startMidi,
            @NotNull @DecimalMin("0") BigDecimal noiseRms,
            @NotNull @DecimalMin("0.1") @DecimalMax("180") BigDecimal durationSeconds,
            @NotBlank @Size(max = 100) String stopReason,
            @NotBlank @Size(max = 50) String lowerBoundaryStatus,
            @NotBlank @Size(max = 50) String upperBoundaryStatus,
            JsonNode attempts,
            @NotBlank @Size(max = 20) String contractVersion,
            JsonNode sampling, JsonNode registerEvents, JsonNode stages,
            JsonNode voiceQuality) {
        public RangeAnalysisUpload(String requestId, int attemptNo, String measurementId,
                LocalDateTime measuredAt, String protocolVersion, String mode, Long parentAnalysisId,
                int startMidi, BigDecimal noiseRms, BigDecimal durationSeconds, String stopReason,
                String lowerBoundaryStatus, String upperBoundaryStatus, JsonNode attempts,
                String contractVersion, JsonNode sampling, JsonNode registerEvents, JsonNode stages) {
            this(requestId, attemptNo, measurementId, measuredAt, protocolVersion, mode,
                    parentAnalysisId, startMidi, noiseRms, durationSeconds, stopReason,
                    lowerBoundaryStatus, upperBoundaryStatus, attempts, contractVersion,
                    sampling, registerEvents, stages, null);
        }
    }
    public record NoteResult(@NotNull BigDecimal midi, @NotBlank String status,
                             BigDecimal accuracy, BigDecimal stability, Integer holdMs) {}
    public record DeviceRangeResult(
            @NotBlank @Size(max = 100) String measurementId,
            @NotNull LocalDateTime measuredAt,
            @NotBlank @Size(max = 50) String protocolVersion,
            BigDecimal lowMidi,
            BigDecimal highMidi,
            @NotNull JsonNode noteResults,
            @NotBlank @Pattern(regexp = "AVAILABLE|INSUFFICIENT_DATA|FAILED") String measurementStatus,
            @NotBlank @Pattern(regexp = "VALID|NOISY|CLIPPED|INSUFFICIENT_VOICE") String qualityStatus,
            JsonNode voiceQuality) {
        public DeviceRangeResult(String measurementId, LocalDateTime measuredAt, String protocolVersion,
                BigDecimal lowMidi, BigDecimal highMidi, JsonNode noteResults,
                String measurementStatus, String qualityStatus) {
            this(measurementId, measuredAt, protocolVersion, lowMidi, highMidi, noteResults,
                    measurementStatus, qualityStatus, null);
        }
    }
}
