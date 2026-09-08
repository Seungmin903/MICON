package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import jakarta.validation.constraints.NotNull;

public record VoiceAnalysisRequest(
        @NotNull Long recordingId,
        @NotNull VoiceAnalysisMethod method,
        Long songId
) {
}
