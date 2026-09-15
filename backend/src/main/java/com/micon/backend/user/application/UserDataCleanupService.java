package com.micon.backend.user.application;

import com.micon.backend.voice.application.VoiceDataDeletionService;
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
                SET storage_path = CONCAT('deleted/', recording_id), sha256 = NULL,
                    file_bytes = NULL, duration_ms = NULL, sample_rate = NULL
                WHERE user_id = ?
                """, userId);
    }
}
