package com.micon.backend.domain.user.service;

import com.micon.backend.domain.voice.service.VoiceDataDeletionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Removes account-owned product data before the account is made inactive. */
@Service
public class UserDataCleanupService {
    private final JdbcTemplate jdbc;
    private final VoiceDataDeletionService voiceData;

    public UserDataCleanupService(JdbcTemplate jdbc, VoiceDataDeletionService voiceData) {
        this.jdbc = jdbc;
        this.voiceData = voiceData;
    }

    public void deleteForWithdrawal(long userId) {
        // Recommendations reference voice analyses, so remove them before voice deletion.
        jdbc.update("DELETE FROM recommendation WHERE user_id = ?", userId);
        voiceData.deleteAll(userId);
        jdbc.update("DELETE FROM practice_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM favorite_song WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM setlist WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_singer_preference WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_genre_preference WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_setting WHERE user_id = ?", userId);
        // Keep a non-identifying tombstone row for referential/audit integrity.
        jdbc.update("""
                UPDATE voice_recording
                SET storage_path = CASE
                        WHEN storage_mode = 'STORED' THEN CONCAT('deleted/', recording_id)
                        ELSE NULL
                    END,
                    sha256 = NULL,
                    file_bytes = NULL, duration_ms = NULL, sample_rate = NULL
                WHERE user_id = ?
                """, userId);
    }
}
