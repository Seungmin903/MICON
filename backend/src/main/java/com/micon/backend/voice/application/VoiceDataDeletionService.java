package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.domain.VocalRangeAnalysisRepository;
import com.micon.backend.voice.domain.VocalTimbreAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysisStatus;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.storage.VoiceStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Service
public class VoiceDataDeletionService {
    private static final Set<VoiceAnalysisStatus> IN_PROGRESS = EnumSet.of(
            VoiceAnalysisStatus.READY, VoiceAnalysisStatus.RECORDING,
            VoiceAnalysisStatus.UPLOADING, VoiceAnalysisStatus.ANALYZING);

    private final VoiceRecordingRepository recordings;
    private final VoiceAnalysisRepository analyses;
    private final VocalRangeAnalysisRepository ranges;
    private final VocalTimbreAnalysisRepository timbres;
    private final VoiceStorage storage;

    public VoiceDataDeletionService(VoiceRecordingRepository recordings, VoiceAnalysisRepository analyses,
                                    VocalRangeAnalysisRepository ranges, VocalTimbreAnalysisRepository timbres,
                                    VoiceStorage storage) {
        this.recordings = recordings;
        this.analyses = analyses;
        this.ranges = ranges;
        this.timbres = timbres;
        this.storage = storage;
    }

    @Transactional
    public void deleteRecording(long userId, long recordingId) {
        VoiceRecording recording = recordings.findByIdAndUserIdForUpdate(recordingId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORDING_NOT_FOUND));
        ensureActive(recording.getUser().getStatus());
        if (recording.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RECORDING_NOT_FOUND);
        }
        if (analyses.existsByRecordingIdAndStatusInAndUserId(recordingId, IN_PROGRESS, userId)) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        }
        LocalDateTime now = LocalDateTime.now();
        recording.markDeletePending(now);
        try {
            storage.delete(recording.getStoragePath());
            recording.markDeleted(now);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.RECORDING_DELETE_FAILED);
        }
    }

    @Transactional
    public void deleteAnalysis(long userId, long analysisId) {
        VoiceAnalysis analysis = analyses.findByIdAndUserIdForUpdate(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        ensureActive(analysis.getUser().getStatus());
        if (IN_PROGRESS.contains(analysis.getStatus())) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        }
        ranges.deleteByVoiceAnalysisId(analysisId);
        timbres.deleteByVoiceAnalysisId(analysisId);
        analyses.delete(analysis);
        analyses.flush();
    }

    private void ensureActive(UserStatus status) {
        if (status != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }
}
