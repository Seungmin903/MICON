package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisStatus;
import com.micon.backend.voice.domain.VoiceQualityStatus;

import java.time.LocalDateTime;

public record VoiceAnalysisResponse(
        Long analysisId,
        Long recordingId,
        Long songId,
        VoiceAnalysisMethod method,
        VoiceAnalysisStatus status,
        VoiceQualityStatus qualityStatus,
        String failureCode,
        String failureReason,
        String analysisVersion,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
    public static VoiceAnalysisResponse from(VoiceAnalysis analysis) {
        return new VoiceAnalysisResponse(
                analysis.getId(), analysis.getRecording().getId(), analysis.getSongId(), analysis.getMethod(),
                analysis.getStatus(), analysis.getQualityStatus(), analysis.getFailureCode(),
                analysis.getFailureReason(), analysis.getAnalysisVersion(), analysis.getStartedAt(),
                analysis.getCompletedAt(), analysis.getCreatedAt());
    }
}
