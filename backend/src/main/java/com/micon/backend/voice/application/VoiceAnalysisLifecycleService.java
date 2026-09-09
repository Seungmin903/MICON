package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.VoiceAnalysisRequest;
import com.micon.backend.voice.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Service
public class VoiceAnalysisLifecycleService {
    private static final Set<VoiceAnalysisStatus> IN_PROGRESS = EnumSet.of(
            VoiceAnalysisStatus.READY, VoiceAnalysisStatus.RECORDING,
            VoiceAnalysisStatus.UPLOADING, VoiceAnalysisStatus.ANALYZING);
    private final VoiceRecordingRepository recordings;
    private final VoiceAnalysisRepository analyses;

    public VoiceAnalysisLifecycleService(VoiceRecordingRepository recordings, VoiceAnalysisRepository analyses) {
        this.recordings = recordings;
        this.analyses = analyses;
    }

    @Transactional
    public VoiceAnalysis create(long userId, VoiceAnalysisRequest request) {
        VoiceRecording recording = recordings.findByIdForUpdate(request.recordingId())
                .filter(candidate -> candidate.getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORDING_NOT_FOUND));
        if (recording.getUser().getStatus() != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        if (recording.getUploadStatus() != RecordingUploadStatus.UPLOADED || recording.getDeletedAt() != null)
            throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
        if (analyses.existsByRecordingIdAndStatusIn(recording.getId(), IN_PROGRESS))
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        try {
            return analyses.saveAndFlush(new VoiceAnalysis(recording.getUser(), recording, request.songId(), request.method()));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
    }

    @Transactional
    public VoiceAnalysis start(long analysisId, String jobId) {
        VoiceAnalysis analysis = locked(analysisId);
        analysis.start(jobId, LocalDateTime.now());
        return analysis;
    }

    @Transactional
    public void fail(long analysisId, String reason) {
        VoiceAnalysis analysis = locked(analysisId);
        analysis.fail("AI_DISPATCH_FAILED", abbreviate(reason), LocalDateTime.now());
    }

    @Transactional
    public String cancel(long userId, long analysisId) {
        VoiceAnalysis analysis = ownedAndLocked(userId, analysisId);
        ensureActiveUser(analysis);
        try {
            analysis.cancel(LocalDateTime.now());
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        return analysis.getJobId();
    }

    @Transactional
    public VoiceAnalysis prepareRetry(long userId, long analysisId) {
        VoiceAnalysis analysis = ownedAndLocked(userId, analysisId);
        ensureActiveUser(analysis);
        VoiceRecording recording = analysis.getRecording();
        if (recording.getUploadStatus() != RecordingUploadStatus.UPLOADED || recording.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
        }
        try {
            analysis.prepareRetry();
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        return analysis;
    }

    private VoiceAnalysis locked(long analysisId) {
        return analyses.findByIdForUpdate(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }

    private VoiceAnalysis ownedAndLocked(long userId, long analysisId) {
        return analyses.findByIdAndUserIdForUpdate(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }

    private void ensureActiveUser(VoiceAnalysis analysis) {
        if (analysis.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    private String abbreviate(String reason) {
        if (reason == null || reason.isBlank()) return "AI server request failed";
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }
}
