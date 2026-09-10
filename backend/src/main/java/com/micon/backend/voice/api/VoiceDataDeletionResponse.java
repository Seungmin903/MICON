package com.micon.backend.voice.api;

import java.time.LocalDateTime;

public final class VoiceDataDeletionResponse {
    private VoiceDataDeletionResponse() {
    }

    public enum Status { COMPLETED, PROCESSING }

    public record Result(
            long deletionId,
            Status status,
            int deletedAnalysisCount,
            int deletedRecordingCount,
            LocalDateTime requestedAt,
            LocalDateTime completedAt
    ) {
    }

    public record State(long deletionId, Status status) {
    }
}
