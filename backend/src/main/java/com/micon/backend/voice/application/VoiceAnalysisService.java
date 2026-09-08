package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.VoiceAnalysisRequest;
import com.micon.backend.voice.api.VoiceAnalysisResponse;
import com.micon.backend.voice.domain.RecordingUploadStatus;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysisStatus;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

@Service
public class VoiceAnalysisService {
    private static final Set<VoiceAnalysisStatus> IN_PROGRESS = EnumSet.of(
            VoiceAnalysisStatus.READY, VoiceAnalysisStatus.RECORDING,
            VoiceAnalysisStatus.UPLOADING, VoiceAnalysisStatus.ANALYZING);

    private final VoiceRecordingRepository recordings;
    private final VoiceAnalysisRepository analyses;

    public VoiceAnalysisService(VoiceRecordingRepository recordings, VoiceAnalysisRepository analyses) {
        this.recordings = recordings;
        this.analyses = analyses;
    }

    @Transactional
    public VoiceAnalysisResponse create(long userId, VoiceAnalysisRequest request) {
        VoiceRecording recording = recordings.findByIdForUpdate(request.recordingId())
                .filter(candidate -> candidate.getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORDING_NOT_FOUND));
        if (recording.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        if (recording.getUploadStatus() != RecordingUploadStatus.UPLOADED || recording.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
        }
        if (analyses.existsByRecordingIdAndStatusIn(recording.getId(), IN_PROGRESS)) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        }
        try {
            return VoiceAnalysisResponse.from(analyses.save(
                    new VoiceAnalysis(recording.getUser(), recording, request.songId(), request.method())));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisResponse get(long userId, long analysisId) {
        VoiceAnalysis analysis = analyses.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (analysis.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return VoiceAnalysisResponse.from(analysis);
    }
}
