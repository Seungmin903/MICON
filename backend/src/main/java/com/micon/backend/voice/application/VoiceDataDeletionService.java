package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.VoiceDataDeletionResponse;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.storage.VoiceStorage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceDataDeletionService {
    private final VoiceRecordingRepository recordings;
    private final VoiceStorage storage;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public VoiceDataDeletionService(VoiceRecordingRepository recordings, VoiceStorage storage,
                                    UserRepository users, JdbcTemplate jdbc) {
        this.recordings = recordings;
        this.storage = storage;
        this.users = users;
        this.jdbc = jdbc;
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
        deleteObject(recording, LocalDateTime.now());
    }

    /** Compatibility for the former analysis route: the id now identifies a v4 session. */
    @Transactional
    public void deleteAnalysis(long userId, long sessionId) {
        Integer running = jdbc.queryForObject("""
                SELECT COUNT(*) FROM voice_analysis_component_job j
                JOIN voice_analysis_session s ON s.session_id = j.session_id
                WHERE s.session_id = ? AND s.user_id = ?
                  AND j.status IN ('PENDING','QUEUED','RUNNING')
                """, Integer.class, sessionId, userId);
        if (running != null && running > 0) throw new BusinessException(ErrorCode.VOICE_ANALYSIS_IN_PROGRESS);
        if (jdbc.update("DELETE FROM voice_analysis_session WHERE session_id = ? AND user_id = ?", sessionId, userId) == 0) {
            throw new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND);
        }
    }

    @Transactional
    public VoiceDataDeletionResponse.Result deleteAll(long userId) {
        ensureActive(users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE)).getStatus());
        List<VoiceRecording> owned = recordings.findActiveByUserIdForUpdate(userId);
        int sessionCount = count("SELECT COUNT(*) FROM voice_analysis_session WHERE user_id = ?", userId);
        LocalDateTime requestedAt = LocalDateTime.now();
        long deletionId = owned.stream().map(VoiceRecording::getId).max(Long::compareTo).orElse(0L);
        jdbc.update("DELETE FROM recommendation WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM voice_profile_save_job WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_voice_profile WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM voice_analysis_session WHERE user_id = ?", userId);
        owned.forEach(recording -> deleteObject(recording, requestedAt));
        recordings.flush();
        return new VoiceDataDeletionResponse.Result(deletionId, VoiceDataDeletionResponse.Status.COMPLETED,
                sessionCount, owned.size(), requestedAt, LocalDateTime.now());
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

    private void deleteObject(VoiceRecording recording, LocalDateTime at) {
        recording.markDeletePending(at);
        try { storage.delete(recording.getStoragePath()); recording.markDeleted(at); }
        catch (RuntimeException exception) { throw new BusinessException(ErrorCode.RECORDING_DELETE_FAILED); }
    }

    private void ensureActive(UserStatus status) {
        if (status != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
    }
}
