CREATE INDEX idx_practice_session_user_song_created
    ON practice_session (user_id, song_id, created_at);
