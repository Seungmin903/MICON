-- Create the application tables that were present in the reference schema but
-- missing from the Flyway-managed database. Names and columns used by the
-- current repositories are preserved (notably favorite_song.prefer_key).

CREATE TABLE social_login (
    social_login_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider ENUM('GOOGLE', 'KAKAO', 'NAVER', 'APPLE') NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    provider_email VARCHAR(320) NULL,
    connected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    disconnected_at DATETIME(6) NULL,
    CONSTRAINT pk_social_login PRIMARY KEY (social_login_id),
    CONSTRAINT uk_social_login_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT uk_social_login_user_provider UNIQUE (user_id, provider),
    CONSTRAINT fk_social_login_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
);

CREATE TABLE user_setting (
    user_id BIGINT NOT NULL,
    save_original_recording BOOLEAN NOT NULL DEFAULT FALSE,
    count_in_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    count_in_seconds SMALLINT UNSIGNED NOT NULL DEFAULT 3,
    vibration_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_setting PRIMARY KEY (user_id),
    CONSTRAINT fk_user_setting_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_setting_count_in
        CHECK (count_in_seconds BETWEEN 0 AND 10)
);

CREATE TABLE favorite_song (
    user_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    prefer_key SMALLINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_favorite_song PRIMARY KEY (user_id, song_id),
    CONSTRAINT fk_favorite_song_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_favorite_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE,
    CONSTRAINT ck_favorite_song_prefer_key
        CHECK (prefer_key IS NULL OR prefer_key BETWEEN -12 AND 12),
    INDEX idx_favorite_song_song (song_id)
);

CREATE TABLE setlist (
    setlist_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_setlist PRIMARY KEY (setlist_id),
    CONSTRAINT fk_setlist_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    INDEX idx_setlist_user_updated (user_id, updated_at)
);

CREATE TABLE setlist_item (
    setlist_item_id BIGINT NOT NULL AUTO_INCREMENT,
    setlist_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    position_no INT UNSIGNED NOT NULL,
    added_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_setlist_item PRIMARY KEY (setlist_item_id),
    CONSTRAINT uk_setlist_item_song UNIQUE (setlist_id, song_id),
    CONSTRAINT uk_setlist_item_position UNIQUE (setlist_id, position_no),
    CONSTRAINT fk_setlist_item_setlist
        FOREIGN KEY (setlist_id) REFERENCES setlist (setlist_id) ON DELETE CASCADE,
    CONSTRAINT fk_setlist_item_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE,
    CONSTRAINT ck_setlist_item_position CHECK (position_no >= 1),
    INDEX idx_setlist_item_song (song_id)
);

CREATE TABLE practice_session (
    practice_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    section_id BIGINT NULL,
    recording_id BIGINT NULL,
    voice_analysis_id BIGINT NULL,
    practice_scope ENUM('FULL_SONG', 'SECTION', 'CUSTOM') NOT NULL DEFAULT 'FULL_SONG',
    selected_key SMALLINT NOT NULL DEFAULT 0,
    playback_speed DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    custom_start_ms INT UNSIGNED NULL,
    custom_end_ms INT UNSIGNED NULL,
    status ENUM('READY', 'IN_PROGRESS', 'PAUSED', 'COMPLETED', 'ABORTED', 'FAILED')
        NOT NULL DEFAULT 'READY',
    failure_code VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    started_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    duration_ms INT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_practice_session PRIMARY KEY (practice_id),
    CONSTRAINT fk_practice_session_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_practice_session_song
        FOREIGN KEY (song_id) REFERENCES song (song_id),
    CONSTRAINT fk_practice_session_section
        FOREIGN KEY (section_id) REFERENCES song_section (section_id) ON DELETE SET NULL,
    CONSTRAINT fk_practice_session_recording
        FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id) ON DELETE SET NULL,
    CONSTRAINT fk_practice_session_voice_analysis
        FOREIGN KEY (voice_analysis_id) REFERENCES voice_analysis (voice_analysis_id) ON DELETE SET NULL,
    CONSTRAINT ck_practice_session_key
        CHECK (selected_key BETWEEN -12 AND 12),
    CONSTRAINT ck_practice_session_speed
        CHECK (playback_speed BETWEEN 0.50 AND 2.00),
    CONSTRAINT ck_practice_session_times
        CHECK (started_at IS NULL OR ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_practice_session_scope
        CHECK (
            (practice_scope IN ('FULL_SONG', 'SECTION')
                AND custom_start_ms IS NULL
                AND custom_end_ms IS NULL)
            OR (practice_scope = 'CUSTOM'
                AND custom_start_ms IS NOT NULL
                AND custom_end_ms IS NOT NULL
                AND custom_end_ms > custom_start_ms)
        ),
    INDEX idx_practice_session_user_created (user_id, created_at),
    INDEX idx_practice_session_song (song_id),
    INDEX idx_practice_session_section (section_id),
    INDEX idx_practice_session_recording (recording_id),
    INDEX idx_practice_session_voice_analysis (voice_analysis_id)
);

CREATE TABLE practice_result (
    practice_result_id BIGINT NOT NULL AUTO_INCREMENT,
    practice_id BIGINT NOT NULL,
    status ENUM('ANALYZING', 'COMPLETED', 'INSUFFICIENT_DATA', 'FAILED')
        NOT NULL DEFAULT 'ANALYZING',
    overall_score DECIMAL(5,2) NULL,
    pitch_score DECIMAL(5,2) NULL,
    sustain_score DECIMAL(5,2) NULL,
    rhythm_score DECIMAL(5,2) NULL,
    summary_text VARCHAR(1000) NULL,
    analyzed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_practice_result PRIMARY KEY (practice_result_id),
    CONSTRAINT uk_practice_result_practice UNIQUE (practice_id),
    CONSTRAINT fk_practice_result_practice
        FOREIGN KEY (practice_id) REFERENCES practice_session (practice_id) ON DELETE CASCADE,
    CONSTRAINT ck_practice_result_overall
        CHECK (overall_score IS NULL OR overall_score BETWEEN 0 AND 100),
    CONSTRAINT ck_practice_result_pitch
        CHECK (pitch_score IS NULL OR pitch_score BETWEEN 0 AND 100),
    CONSTRAINT ck_practice_result_sustain
        CHECK (sustain_score IS NULL OR sustain_score BETWEEN 0 AND 100),
    CONSTRAINT ck_practice_result_rhythm
        CHECK (rhythm_score IS NULL OR rhythm_score BETWEEN 0 AND 100)
);
