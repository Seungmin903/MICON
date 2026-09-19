package com.micon.backend.domain.voice.service;

import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.voice.dto.VoiceDataDeletionResponse;
import com.micon.backend.domain.voice.entity.VoiceRecording;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceDataDeletionService {
    private final VoiceRecordingRepository recordings;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final VoiceRecordingDeletionLifecycle deletionLifecycle;
    private final ApplicationEventPublisher events;

    public VoiceDataDeletionService(VoiceRecordingRepository recordings, UserRepository users, JdbcTemplate jdbc,
                                    VoiceRecordingDeletionLifecycle deletionLifecycle,
                                    ApplicationEventPublisher events) {
        this.recordings = recordings;
        this.users = users;
        this.jdbc = jdbc;
        this.deletionLifecycle = deletionLifecycle;
        this.events = events;
    }

    @Transactional
    public void deleteRecording(long userId, long recordingId) {
        VoiceRecording recording = recordings.findByIdAndUserIdForUpdate(recordingId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORDING_NOT_FOUND));
        ensureActive(recording.getUser().getStatus());
        Integer running = jdbc.queryForObject("""
                SELECT COUNT(*) FROM voice_analysis_component_job j
                JOIN voice_recording_context c ON c.session_id = j.session_id
                WHERE c.recording_id = ? AND j.status IN ('PENDING','QUEUED','RUNNING')
                """, Integer.class, recordingId);
        if (running != null && running > 0) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        enqueueDeletion(recording, LocalDateTime.now());
    }

    /** 이전 음성 분석 삭제 계약을 세션 식별자 기반으로 처리하는 호환 경로다. */
    @Transactional
    public void deleteAnalysis(long userId, long sessionId) {
        Integer running = jdbc.queryForObject("""
                SELECT COUNT(*) FROM voice_analysis_component_job j
                JOIN voice_analysis_session s ON s.session_id = j.session_id
                WHERE s.session_id = ? AND s.user_id = ?
                  AND j.status IN ('PENDING','QUEUED','RUNNING')
                """, Integer.class, sessionId, userId);
        if (running != null && running > 0) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        List<Long> recordingIds = jdbc.queryForList("""
                SELECT recording_id FROM voice_recording_context WHERE session_id = ?
                """, Long.class, sessionId);
        jdbc.update("DELETE FROM voice_recording_context WHERE session_id = ?", sessionId);
        if (jdbc.update("DELETE FROM voice_analysis_session WHERE session_id = ? AND user_id = ?", sessionId, userId) == 0) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND);
        }
        LocalDateTime requestedAt = LocalDateTime.now();
        recordingIds.forEach(recordingId -> recordings.findByIdAndUserIdForUpdate(recordingId, userId)
                .ifPresent(recording -> enqueueDeletion(recording, requestedAt)));
    }

    @Transactional
    public VoiceDataDeletionResponse.Result deleteAll(long userId) {
        ensureActive(users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)).getStatus());
        List<VoiceRecording> owned = recordings.findActiveByUserIdForUpdate(userId);
        int sessionCount = count("SELECT COUNT(*) FROM voice_analysis_session WHERE user_id = ?", userId);
        LocalDateTime requestedAt = LocalDateTime.now();
        long deletionId = owned.stream().map(VoiceRecording::getId).max(Long::compareTo).orElse(0L);
        List<Long> queued = new ArrayList<>();
        jdbc.update("DELETE FROM recommendation WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM voice_profile_save_job WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_voice_profile WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM voice_recording_context WHERE session_id IN "
                + "(SELECT session_id FROM voice_analysis_session WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM voice_analysis_session WHERE user_id = ?", userId);
        owned.forEach(recording -> {
            Long jobId = enqueueDeletion(recording, requestedAt);
            if (jobId != null) queued.add(jobId);
        });
        recordings.flush();
        var status = queued.isEmpty() ? VoiceDataDeletionResponse.Status.COMPLETED
                : VoiceDataDeletionResponse.Status.PROCESSING;
        return new VoiceDataDeletionResponse.Result(deletionId, status, sessionCount, owned.size(),
                requestedAt, queued.isEmpty() ? LocalDateTime.now() : null);
    }

    @Transactional(readOnly = true)
    public VoiceDataDeletionResponse.State status(long userId, long deletionId) {
        ensureActive(users.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)).getStatus());
        long remaining = recordings.countByUserIdAndIdLessThanEqualAndDeletedAtIsNull(userId, deletionId);
        int sessions = count("SELECT COUNT(*) FROM voice_analysis_session WHERE user_id = ?", userId);
        var state = remaining == 0 && sessions == 0
                ? VoiceDataDeletionResponse.Status.COMPLETED : VoiceDataDeletionResponse.Status.PROCESSING;
        return new VoiceDataDeletionResponse.State(deletionId, state);
    }

    private int count(String sql, long userId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId);
        return value == null ? 0 : value;
    }

    private Long enqueueDeletion(VoiceRecording recording, LocalDateTime at) {
        Long jobId = deletionLifecycle.enqueue(recording, at);
        if (jobId != null) events.publishEvent(new VoiceRecordingDeletionQueuedEvent(jobId));
        return jobId;
    }

    private void ensureActive(UserStatus status) {
        if (status != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
    }
}
