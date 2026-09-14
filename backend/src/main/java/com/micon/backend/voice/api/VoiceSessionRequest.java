package com.micon.backend.voice.api;

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
            @NotNull JsonNode attempts,
            @NotBlank @Size(max = 20) String contractVersion) {}
    public record NoteResult(@NotNull BigDecimal midi, @NotBlank String status,
                             BigDecimal accuracy, BigDecimal stability, Integer holdMs) {}
}
