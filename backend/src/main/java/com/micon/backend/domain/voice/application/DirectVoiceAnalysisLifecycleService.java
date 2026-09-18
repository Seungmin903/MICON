package com.micon.backend.domain.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.user.domain.UserStatus;
import com.micon.backend.domain.voice.api.VoiceSessionRequest;
import com.micon.backend.domain.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.domain.voice.domain.*;
import com.micon.backend.voice.domain.*;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Lifecycle for the standalone range component. Timbre parent jobs use TimbreAnalysisLifecycleService. */
@Service
public class DirectVoiceAnalysisLifecycleService {
    private final VoiceAnalysisSessionRepository sessions;
    private final VoiceAnalysisComponentJobRepository jobs;
    private final VoiceRecordingRepository recordings;

    public DirectVoiceAnalysisLifecycleService(VoiceAnalysisSessionRepository sessions,
                                               VoiceAnalysisComponentJobRepository jobs,
                                               VoiceRecordingRepository recordings,
                                               VoiceRecordingContextRepository contexts,
                                               VoiceRecordingSegmentRepository segments,
                                               org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.jobs = jobs;
        this.recordings = recordings;
    }

    @Transactional
    public PreparedJob createRangePending(long userId, long sessionId, MultipartFile file,
                                          VoiceSessionRequest.RangeAnalysisUpload request) {
        VoiceAnalysisComponentJob existing = jobs.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.getSession().getId().equals(sessionId)
                    || !existing.getSession().getUser().getId().equals(userId)
                    || !"RANGE".equals(existing.getComponentType())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 다른 분석에 사용된 requestId입니다.");
            }
            return new PreparedJob(existing.getId(), existing.getStatus(), VoiceAnalysisJobResponse.from(existing));
        }
        VoiceAnalysisSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (session.getUser().getStatus() != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        if (session.isExpired()) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE,
                "만료된 음성 분석 세션입니다.");
        VoiceRecording recording = recordings.saveAndFlush(VoiceRecording.metadataOnly(session.getUser(),
                (short) request.attemptNo(), file.getContentType(), file.getSize(),
                request.durationSeconds().multiply(java.math.BigDecimal.valueOf(1000)).intValue(), 16_000));
        VoiceAnalysisComponentJob saved = jobs.saveAndFlush(new VoiceAnalysisComponentJob(session, recording,
                "RANGE", request.attemptNo(), request.requestId()));
        session.beginRangeAnalysis();
        return new PreparedJob(saved.getId(), saved.getStatus(), VoiceAnalysisJobResponse.from(saved));
    }

    @Transactional
    public VoiceAnalysisJobResponse queuedRange(long userId, long analysisId, String aiJobId) {
        VoiceAnalysisComponentJob job = ownedForUpdate(userId, analysisId);
        job.queued(aiJobId, LocalDateTime.now());
        if (!job.isTerminal()) job.getSession().queueRangeAnalysis();
        return VoiceAnalysisJobResponse.from(job);
    }

    @Transactional
    public void rangeDispatchUnknown(long userId, long analysisId, String reason) {
        ownedForUpdate(userId, analysisId).dispatchUnknown(limit(reason), LocalDateTime.now());
    }

    @Transactional
    public int failTimedOut(LocalDateTime cutoff) {
        List<VoiceAnalysisComponentJob> timedOut = jobs.findTimedOutForUpdate(cutoff).stream()
                .filter(job -> "RANGE".equals(job.getComponentType())).limit(100).toList();
        LocalDateTime now = LocalDateTime.now();
        timedOut.forEach(job -> {
            job.fail("AI_TIMEOUT", "AI 분석 최대 대기 시간을 초과했습니다. 녹음 파일을 다시 제출해 주세요.", now);
            job.getSession().failRangeAnalysis(now);
        });
        return timedOut.size();
    }

    private VoiceAnalysisComponentJob ownedForUpdate(long userId, long analysisId) {
        return jobs.findOwnedByIdForUpdate(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record PreparedJob(Long analysisId, String status, VoiceAnalysisJobResponse response) { }
}
