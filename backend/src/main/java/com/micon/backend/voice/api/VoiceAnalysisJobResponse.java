package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.VoiceAnalysisComponentJob;
import com.micon.backend.voice.domain.VoiceAnalysisJob;
import java.time.LocalDateTime;

public record VoiceAnalysisJobResponse(
        Long analysisId,
        Long sessionId,
        Long recordingId,
        String requestId,
        String aiJobId,
        String componentType,
        String status,
        String failureCode,
        String failureReason,
        boolean reuploadRequired,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static VoiceAnalysisJobResponse from(VoiceAnalysisComponentJob job) {
        return new VoiceAnalysisJobResponse(job.getId(), job.getSession().getId(),
                job.getRecording() == null ? null : job.getRecording().getId(), job.getRequestId(),
                job.getAiJobId(), job.getComponentType(), job.getStatus(), job.getFailureCode(),
                job.getFailureReason(), "FAILED".equals(job.getStatus()), job.getStartedAt(),
                job.getCompletedAt());
    }
    public static VoiceAnalysisJobResponse from(VoiceAnalysisJob job) {
        return new VoiceAnalysisJobResponse(job.getId(), job.getSession().getId(),
                job.getRecording()==null?null:job.getRecording().getId(), job.getRequestId(), job.getAiJobId(),
                "TIMBRE", job.getStatus(), job.getFailureCode(), job.getFailureReason(),
                "FAILED".equals(job.getStatus()), job.getStartedAt(), job.getCompletedAt());
    }
}
