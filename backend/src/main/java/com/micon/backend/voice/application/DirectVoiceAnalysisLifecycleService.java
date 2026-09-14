package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.api.VoiceSessionRequest;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJob;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJobRepository;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisSession;
import com.micon.backend.voice.domain.VoiceAnalysisSessionRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingContext;
import com.micon.backend.voice.domain.VoiceRecordingContextRepository;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.domain.VoiceRecordingSegment;
import com.micon.backend.voice.domain.VoiceRecordingSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DirectVoiceAnalysisLifecycleService {
    private final VoiceAnalysisSessionRepository sessions;
    private final VoiceAnalysisComponentJobRepository jobs;
    private final VoiceRecordingRepository recordings;
    private final VoiceRecordingContextRepository contexts;
    private final VoiceRecordingSegmentRepository segments;
    private final JdbcTemplate jdbc;

    public DirectVoiceAnalysisLifecycleService(VoiceAnalysisSessionRepository sessions,
                                               VoiceAnalysisComponentJobRepository jobs,
                                               VoiceRecordingRepository recordings,
                                               VoiceRecordingContextRepository contexts,
                                               VoiceRecordingSegmentRepository segments,
                                               JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.jobs = jobs;
        this.recordings = recordings;
        this.contexts = contexts;
        this.segments = segments;
        this.jdbc = jdbc;
    }

    @Transactional
    public PreparedJob createPending(long userId, long sessionId, MultipartFile file,
                                     VoiceAnalysisUploadRequest request) {
        VoiceAnalysisComponentJob existing = jobs.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.getSession().getId().equals(sessionId)
                    || !existing.getSession().getUser().getId().equals(userId)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 다른 분석에 사용된 requestId입니다.");
            }
            return new PreparedJob(existing.getId(), existing.getStatus(), VoiceAnalysisJobResponse.from(existing));
        }
        VoiceAnalysisSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (session.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        if (session.isExpired()) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE, "만료된 음성 분석 세션입니다.");
        }
        if (jobs.findBySessionIdOrderByComponentTypeAscAttemptNoDesc(sessionId).stream()
                .anyMatch(job -> !job.isTerminal())) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        }
        verifySong(request);
        VoiceRecording recording = recordings.saveAndFlush(VoiceRecording.metadataOnly(session.getUser(),
                nextAttempt(sessionId), file.getContentType(), file.getSize(), request.durationMs(), request.sampleRate()));
        saveContext(recording, session, request);
        String componentType = request.method() == VoiceAnalysisMethod.SONG ? "SINGER_BYOL" : "ECAPA";
        VoiceAnalysisComponentJob job = new VoiceAnalysisComponentJob(session, recording, componentType,
                recording.getAttemptNo(), request.requestId());
        session.beginTimbreAnalysis();
        try {
            VoiceAnalysisComponentJob saved = jobs.saveAndFlush(job);
            return new PreparedJob(saved.getId(), saved.getStatus(), VoiceAnalysisJobResponse.from(saved));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "중복된 분석 요청입니다.");
        }
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
        if (session.isExpired()) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE, "만료된 음성 분석 세션입니다.");
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
        job.getSession().queueRangeAnalysis();
        return VoiceAnalysisJobResponse.from(job);
    }

    @Transactional
    public void rangeDispatchUnknown(long userId, long analysisId, String reason) {
        VoiceAnalysisComponentJob job = ownedForUpdate(userId, analysisId);
        job.dispatchUnknown(limit(reason), LocalDateTime.now());
    }

    @Transactional
    public VoiceAnalysisJobResponse queued(long userId, long analysisId, String aiJobId) {
        VoiceAnalysisComponentJob job = ownedForUpdate(userId, analysisId);
        job.queued(aiJobId, LocalDateTime.now());
        if (!job.isTerminal()) job.getSession().acceptTimbreAnalysis();
        return VoiceAnalysisJobResponse.from(job);
    }

    @Transactional
    public void dispatchFailed(long userId, long analysisId, String reason) {
        VoiceAnalysisComponentJob job = ownedForUpdate(userId, analysisId);
        if (!job.isTerminal()) {
            LocalDateTime now = LocalDateTime.now();
            job.fail("AI_DISPATCH_FAILED", limit(reason), now);
            job.getSession().failTimbreAnalysis(false, now);
        }
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisJobResponse latest(long userId, long sessionId) {
        VoiceAnalysisComponentJob job = jobs.findFirstBySessionIdOrderByAttemptNoDesc(sessionId)
                .filter(found -> found.getSession().getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        return VoiceAnalysisJobResponse.from(job);
    }

    @Transactional
    public CancelResult cancel(long userId, long sessionId, long analysisId) {
        VoiceAnalysisComponentJob job = ownedForUpdate(userId, analysisId);
        if (!job.getSession().getId().equals(sessionId)) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND);
        }
        String aiJobId = job.getAiJobId();
        if (!job.isTerminal()) {
            LocalDateTime now = LocalDateTime.now();
            job.cancel(now);
            job.getSession().cancelTimbreAnalysis(now);
        }
        return new CancelResult(VoiceAnalysisJobResponse.from(job), aiJobId);
    }

    @Transactional
    public int failTimedOut(LocalDateTime cutoff) {
        List<VoiceAnalysisComponentJob> timedOut = jobs.findTimedOutForUpdate(cutoff).stream().limit(100).toList();
        LocalDateTime now = LocalDateTime.now();
        timedOut.forEach(job -> {
            job.fail("AI_TIMEOUT", "AI 분석 최대 대기 시간을 초과했습니다. 녹음 파일을 다시 제출해 주세요.", now);
            job.getSession().failTimbreAnalysis(false, now);
        });
        return timedOut.size();
    }

    private VoiceAnalysisComponentJob ownedForUpdate(long userId, long analysisId) {
        return jobs.findOwnedByIdForUpdate(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }

    private short nextAttempt(long sessionId) {
        int attempt = jobs.findBySessionIdOrderByComponentTypeAscAttemptNoDesc(sessionId).stream()
                .mapToInt(VoiceAnalysisComponentJob::getAttemptNo).max().orElse(0) + 1;
        if (attempt > Short.MAX_VALUE) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        return (short) attempt;
    }

    private void verifySong(VoiceAnalysisUploadRequest request) {
        if (request.songId() == null) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM song WHERE song_id = ?", Integer.class,
                request.songId());
        if (count == null || count == 0) throw new BusinessException(ErrorCode.SONG_NOT_FOUND);
    }

    private void saveContext(VoiceRecording recording, VoiceAnalysisSession session,
                             VoiceAnalysisUploadRequest request) {
        if (request.songId() == null) return;
        contexts.save(new VoiceRecordingContext(recording, session, request.songId(),
                request.startPositionType(), request.selectedStartMs(), request.actualStartMs(),
                request.durationMs(), request.instrumentalVersion(), request.lyricsVersion(), request.requestId()));
        List<VoiceAnalysisUploadRequest.Segment> requested = request.segments();
        if (requested == null || requested.isEmpty()) return;
        short sequence = 1;
        for (VoiceAnalysisUploadRequest.Segment segment : requested) {
            segments.save(new VoiceRecordingSegment(recording, sequence++, segment.recordingStartMs(),
                    segment.recordingEndMs(), segment.songStartMs(), segment.songEndMs()));
        }
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record CancelResult(VoiceAnalysisJobResponse response, String aiJobId) { }
    public record PreparedJob(Long analysisId, String status, VoiceAnalysisJobResponse response) { }
}
