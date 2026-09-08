package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.*;
import java.time.LocalDateTime;
import java.util.List;

public final class VoiceAnalysisHistoryResponse {
    private VoiceAnalysisHistoryResponse() {}

    public record Page(List<Summary> content, int page, int size, long totalElements, int totalPages) {}

    public record Summary(Long analysisId, Long recordingId, Long songId, VoiceAnalysisMethod method,
                          VoiceAnalysisStatus status, VoiceQualityStatus qualityStatus,
                          String failureCode, String failureReason, LocalDateTime startedAt,
                          LocalDateTime completedAt, LocalDateTime createdAt) {
        public static Summary from(VoiceAnalysis analysis) {
            return new Summary(analysis.getId(), analysis.getRecording().getId(), analysis.getSongId(),
                    analysis.getMethod(), analysis.getStatus(), analysis.getQualityStatus(),
                    analysis.getFailureCode(), analysis.getFailureReason(), analysis.getStartedAt(),
                    analysis.getCompletedAt(), analysis.getCreatedAt());
        }
    }
}
