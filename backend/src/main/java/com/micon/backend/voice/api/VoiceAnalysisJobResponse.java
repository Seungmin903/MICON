package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.VoiceAnalysisComponentJob;
import com.micon.backend.voice.domain.VoiceAnalysisJob;
import java.time.LocalDateTime;
import java.util.List;

public record VoiceAnalysisJobResponse(
        Long analysisId, Long sessionId, Long recordingId, String requestId, String aiJobId,
        String componentType, String status, String failureCode, String failureReason,
        boolean reuploadRequired, LocalDateTime startedAt, LocalDateTime completedAt,
        LocalDateTime audioExpiresAt, boolean duplicate, List<Component> components
) {
    public static VoiceAnalysisJobResponse from(VoiceAnalysisComponentJob job) {
        return new VoiceAnalysisJobResponse(job.getId(), job.getSession().getId(),
                job.getRecording() == null ? null : job.getRecording().getId(), job.getRequestId(),
                job.getAiJobId(), job.getComponentType(), job.getStatus(), job.getFailureCode(),
                job.getFailureReason(), "FAILED".equals(job.getStatus()), job.getStartedAt(),
                job.getCompletedAt(), null, false, List.of());
    }

    public static VoiceAnalysisJobResponse from(VoiceAnalysisJob job) {
        return from(job, List.of());
    }

    public static VoiceAnalysisJobResponse from(VoiceAnalysisJob job,
                                                List<VoiceAnalysisComponentJob> components) {
        boolean reupload = "AUDIO_EXPIRED".equals(job.getFailureCode()) || "FAILED".equals(job.getStatus());
        return new VoiceAnalysisJobResponse(job.getId(), job.getSession().getId(),
                job.getRecording() == null ? null : job.getRecording().getId(), job.getRequestId(),
                job.getAiJobId(), "TIMBRE", job.getStatus(), job.getFailureCode(), job.getFailureReason(),
                reupload, job.getStartedAt(), job.getCompletedAt(), job.getAudioExpiresAt(),
                job.isDuplicate(), components.stream().map(Component::from).toList());
    }

    public record Component(Long componentJobId, String component, String status,
                            String failureCode, String failureReason, String modelVersion) {
        private static Component from(VoiceAnalysisComponentJob job) {
            return new Component(job.getId(), job.getComponentType(), job.getStatus(),
                    job.getFailureCode(), job.getFailureReason(), job.getModelVersion());
        }
    }
}
