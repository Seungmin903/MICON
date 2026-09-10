package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.practice.repository.PracticeRepository;
import com.micon.backend.voice.api.VoiceDataDeletionResponse;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class VoiceDataDeletionService {
    private static final Logger log = LoggerFactory.getLogger(VoiceDataDeletionService.class);
    private static final Set<VoiceAnalysisStatus> IN_PROGRESS = EnumSet.of(
            VoiceAnalysisStatus.READY, VoiceAnalysisStatus.RECORDING,
            VoiceAnalysisStatus.UPLOADING, VoiceAnalysisStatus.ANALYZING);

    private final VoiceRecordingRepository recordings;
    private final VoiceAnalysisRepository analyses;
    private final VocalRangeAnalysisRepository ranges;
    private final VocalTimbreAnalysisRepository timbres;
    private final VoiceStorage storage;
    private final UserRepository users;
    private final PracticeRepository practices;
    private final VoiceAnalysisJobGateway aiGateway;

    public VoiceDataDeletionService(VoiceRecordingRepository recordings, VoiceAnalysisRepository analyses,
                                    VocalRangeAnalysisRepository ranges, VocalTimbreAnalysisRepository timbres,
                                    VoiceStorage storage, UserRepository users, PracticeRepository practices,
                                    VoiceAnalysisJobGateway aiGateway) {
        this.recordings = recordings;
        this.analyses = analyses;
        this.ranges = ranges;
        this.timbres = timbres;
        this.storage = storage;
        this.users = users;
        this.practices = practices;
        this.aiGateway = aiGateway;
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

    @Transactional
    public VoiceDataDeletionResponse.Result deleteAll(long userId) {
        ensureActive(users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE))
                .getStatus());
        List<VoiceRecording> ownedRecordings = recordings.findActiveByUserIdForUpdate(userId);
        List<VoiceAnalysis> ownedAnalyses = analyses.findByUserIdForUpdate(userId);
        LocalDateTime requestedAt = LocalDateTime.now();
        long deletionId = ownedRecordings.stream()
                .map(VoiceRecording::getId)
                .max(Long::compareTo)
                .orElse(0L);

        try {
            cancelActiveJobs(ownedAnalyses);
            List<Long> analysisIds = ownedAnalyses.stream().map(VoiceAnalysis::getId).toList();
            practices.clearVoiceAnalysisReferences(analysisIds);
            for (Long analysisId : analysisIds) {
                ranges.deleteByVoiceAnalysisId(analysisId);
                timbres.deleteByVoiceAnalysisId(analysisId);
            }
            analyses.deleteAll(ownedAnalyses);
            analyses.flush();

            for (VoiceRecording recording : ownedRecordings) {
                recording.markDeletePending(requestedAt);
                storage.delete(recording.getStoragePath());
                recording.markDeleted(requestedAt);
            }
            recordings.flush();
        } catch (RuntimeException exception) {
            log.warn("Failed to delete all voice data for user {}", userId, exception);
            throw new BusinessException(ErrorCode.VOICE_DATA_DELETION_FAILED);
        }

        return new VoiceDataDeletionResponse.Result(
                deletionId,
                VoiceDataDeletionResponse.Status.COMPLETED,
                ownedAnalyses.size(),
                ownedRecordings.size(),
                requestedAt,
                LocalDateTime.now()
        );
    }

    @Transactional(readOnly = true)
    public VoiceDataDeletionResponse.State status(long userId, long deletionId) {
        ensureActive(users.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE))
                .getStatus());
        if (deletionId < 0 || (deletionId > 0 && !recordings.existsByIdAndUserId(deletionId, userId))) {
            throw new BusinessException(ErrorCode.VOICE_DATA_DELETION_NOT_FOUND);
        }
        long remainingRecordings = recordings.countByUserIdAndIdLessThanEqualAndDeletedAtIsNull(userId, deletionId);
        long remainingAnalyses = analyses.countByUserIdAndRecordingIdLessThanEqual(userId, deletionId);
        VoiceDataDeletionResponse.Status status = remainingRecordings == 0 && remainingAnalyses == 0
                ? VoiceDataDeletionResponse.Status.COMPLETED
                : VoiceDataDeletionResponse.Status.PROCESSING;
        return new VoiceDataDeletionResponse.State(deletionId, status);
    }

    private void cancelActiveJobs(List<VoiceAnalysis> ownedAnalyses) {
        for (VoiceAnalysis analysis : ownedAnalyses) {
            if (IN_PROGRESS.contains(analysis.getStatus())
                    && analysis.getJobId() != null && !analysis.getJobId().isBlank()) {
                aiGateway.cancel(analysis.getJobId());
            }
        }
    }

    private void ensureActive(UserStatus status) {
        if (status != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }
}
