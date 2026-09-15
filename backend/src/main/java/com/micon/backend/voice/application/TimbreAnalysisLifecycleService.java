package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.domain.*;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TimbreAnalysisLifecycleService {
    public static final List<String> MODEL_COMPONENTS = List.of("ECAPA", "SINGER_BYOL");
    private final VoiceAnalysisSessionRepository sessions;
    private final VoiceAnalysisJobRepository parents;
    private final VoiceAnalysisComponentJobRepository components;
    private final VoiceRecordingRepository recordings;
    private final VoiceRecordingContextRepository contexts;
    private final VoiceRecordingSegmentRepository segments;
    private final JdbcTemplate jdbc;

    public TimbreAnalysisLifecycleService(VoiceAnalysisSessionRepository sessions,
            VoiceAnalysisJobRepository parents, VoiceAnalysisComponentJobRepository components,
            VoiceRecordingRepository recordings, VoiceRecordingContextRepository contexts,
            VoiceRecordingSegmentRepository segments, JdbcTemplate jdbc) {
        this.sessions = sessions; this.parents = parents; this.components = components;
        this.recordings = recordings; this.contexts = contexts; this.segments = segments; this.jdbc = jdbc;
    }

    @Transactional
    public Prepared create(long userId, long sessionId, MultipartFile file,
                           VoiceAnalysisUploadRequest request, String audioSha256) {
        VoiceAnalysisJob existing = parents.findByRequestIdForUpdate(request.requestId()).orElse(null);
        if (existing != null) {
            ensureOwner(existing, userId, sessionId);
            if (!audioSha256.equals(existing.getAudioSha256())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return prepared(existing, existing.canDispatch());
        }
        VoiceAnalysisSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (session.getUser().getStatus() != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        if (session.isExpired()) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        if (parents.findFirstBySessionIdOrderByAttemptNoDesc(sessionId).filter(job -> !job.terminal()).isPresent()) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM song WHERE song_id = ?", Integer.class,
                request.songId());
        if (count == null || count == 0) throw new BusinessException(ErrorCode.SONG_NOT_FOUND);
        int attemptNo = parents.findFirstBySessionIdOrderByAttemptNoDesc(sessionId)
                .map(job -> job.getAttemptNo() + 1).orElse(1);
        VoiceRecording recording = recordings.saveAndFlush(VoiceRecording.metadataOnly(session.getUser(),
                (short) attemptNo, file.getContentType(), file.getSize(), request.durationMs(), request.sampleRate()));
        contexts.save(new VoiceRecordingContext(recording, session, request.songId(), request.startPositionType(),
                request.selectedStartMs(), request.actualStartMs(), request.durationMs(),
                request.instrumentalVersion(), request.lyricsVersion(), request.requestId()));
        short sequence = 1;
        if (request.segments() != null) {
            for (VoiceAnalysisUploadRequest.Segment segment : request.segments()) {
                segments.save(new VoiceRecordingSegment(recording, sequence++, segment.recordingStartMs(),
                        segment.recordingEndMs(), segment.songStartMs(), segment.songEndMs()));
            }
        }
        session.beginTimbreAnalysis();
        try {
            VoiceAnalysisJob parent = parents.saveAndFlush(new VoiceAnalysisJob(session, recording,
                    request.requestId(), audioSha256, attemptNo));
            components.saveAll(MODEL_COMPONENTS.stream()
                    .map(component -> new VoiceAnalysisComponentJob(parent, component)).toList());
            components.flush();
            return prepared(parent, true);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "중복된 분석 요청입니다.");
        }
    }

    @Transactional
    public VoiceAnalysisJobResponse queued(long userId, long analysisId, VoiceAnalysisJobGateway.Result result) {
        VoiceAnalysisJob parent = owned(userId, analysisId);
        LocalDateTime now = LocalDateTime.now();
        parent.queued(result.jobId(), result.status(), result.audioExpiresAt(), result.duplicate(), now);
        List<VoiceAnalysisComponentJob> children = components.findByAnalysisJobIdForUpdate(analysisId);
        children.forEach(child -> child.queued(now));
        if (!parent.terminal()) parent.getSession().acceptTimbreAnalysis();
        return VoiceAnalysisJobResponse.from(parent, children);
    }

    @Transactional
    public void unknown(long userId, long analysisId, String reason) {
        owned(userId, analysisId).dispatchUnknown(limit(reason), LocalDateTime.now());
    }

    @Transactional
    public void fail(long userId, long analysisId, String code, String reason) {
        VoiceAnalysisJob parent = owned(userId, analysisId);
        if (parent.terminal()) return;
        LocalDateTime now = LocalDateTime.now();
        parent.fail(code, limit(reason), now);
        components.findByAnalysisJobIdForUpdate(analysisId)
                .forEach(child -> child.fail(code, limit(reason), now));
        parent.getSession().failTimbreAnalysis(false, now);
    }

    @Transactional(readOnly = true)
    public VoiceAnalysisJobResponse latest(long userId, long sessionId) {
        VoiceAnalysisJob parent = parents.findFirstBySessionIdOrderByAttemptNoDesc(sessionId)
                .filter(job -> job.getSession().getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        return response(parent);
    }

    @Transactional
    public CancelResult cancel(long userId, long sessionId, long analysisId) {
        VoiceAnalysisJob parent = owned(userId, analysisId);
        if (!parent.getSession().getId().equals(sessionId)) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND);
        LocalDateTime now = LocalDateTime.now();
        if (!parent.terminal()) {
            parent.cancel(now);
            components.findByAnalysisJobIdForUpdate(analysisId).forEach(child -> child.cancel(now));
            parent.getSession().cancelTimbreAnalysis(now);
        }
        return new CancelResult(response(parent), parent.getAiJobId());
    }

    @Transactional
    public int failTimedOut(LocalDateTime cutoff) {
        List<VoiceAnalysisJob> timedOut = parents.findTimedOutForUpdate(cutoff).stream().limit(100).toList();
        LocalDateTime now = LocalDateTime.now();
        timedOut.forEach(parent -> {
            parent.fail("AI_TIMEOUT", "AI 분석 최대 대기 시간을 초과했습니다. 녹음 파일을 다시 제출해 주세요.", now);
            components.findByAnalysisJobIdForUpdate(parent.getId()).forEach(child ->
                    child.fail("AI_TIMEOUT", "AI 분석 최대 대기 시간을 초과했습니다.", now));
            parent.getSession().failTimbreAnalysis(false, now);
        });
        return timedOut.size();
    }

    private Prepared prepared(VoiceAnalysisJob parent, boolean shouldDispatch) {
        return new Prepared(parent.getId(), parent.getSession().getId(),
                parent.getRecording() == null ? null : parent.getRecording().getId(), parent.getRequestId(),
                parent.getAttemptNo(), parent.getStatus(), shouldDispatch, response(parent));
    }

    private VoiceAnalysisJobResponse response(VoiceAnalysisJob parent) {
        return VoiceAnalysisJobResponse.from(parent,
                components.findByAnalysisJobIdOrderByComponentTypeAsc(parent.getId()));
    }

    private VoiceAnalysisJob owned(long userId, long analysisId) {
        return parents.findOwnedForUpdate(analysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }

    private void ensureOwner(VoiceAnalysisJob parent, long userId, long sessionId) {
        if (!parent.getSession().getId().equals(sessionId)
                || !parent.getSession().getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record Prepared(Long analysisId, Long sessionId, Long recordingId, String requestId,
                           int attemptNo, String status, boolean shouldDispatch,
                           VoiceAnalysisJobResponse response) { }
    public record CancelResult(VoiceAnalysisJobResponse response, String aiJobId) { }
}
