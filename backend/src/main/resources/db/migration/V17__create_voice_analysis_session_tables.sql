-- MICON v4 voice onboarding model.
-- Only member-owned, persistent analysis data belongs in MySQL.
-- Guest sessions/results must remain in TTL cache and temporary object storage.

CREATE TABLE voice_analysis_session (
    session_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    song_id BIGINT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    range_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    timbre_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    artist_search_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    save_status VARCHAR(30) NOT NULL DEFAULT 'NOT_READY',
    result_version BIGINT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_analysis_session PRIMARY KEY (session_id),
    CONSTRAINT uk_voice_analysis_session_owner UNIQUE (session_id, user_id),
    CONSTRAINT fk_voice_analysis_session_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_analysis_session_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE SET NULL,
    CONSTRAINT ck_voice_analysis_session_status CHECK (
        status IN (
            'CREATED', 'RANGE_IN_PROGRESS', 'TIMBRE_RECORDING', 'UPLOADING',
            'ANALYZING', 'PARTIAL_READY', 'READY_TO_SAVE', 'SAVING', 'SAVED',
            'INSUFFICIENT_DATA', 'FAILED', 'CANCELED', 'EXPIRED'
        )
    ),
    CONSTRAINT ck_voice_analysis_session_range_status CHECK (
        range_status IN ('PENDING', 'IN_PROGRESS', 'AVAILABLE', 'INSUFFICIENT_DATA', 'FAILED')
    ),
    CONSTRAINT ck_voice_analysis_session_timbre_status CHECK (
        timbre_status IN ('PENDING', 'RECORDING', 'UPLOADING', 'ANALYZING', 'AVAILABLE', 'INSUFFICIENT_DATA', 'FAILED')
    ),
    CONSTRAINT ck_voice_analysis_session_artist_status CHECK (
        artist_search_status IN ('PENDING', 'RUNNING', 'COMPLETED', 'NO_RESULT', 'FAILED')
    ),
    CONSTRAINT ck_voice_analysis_session_save_status CHECK (
        save_status IN ('NOT_READY', 'READY', 'SAVING', 'SAVED', 'FAILED')
    ),
    CONSTRAINT ck_voice_analysis_session_result_version CHECK (result_version >= 0),
    CONSTRAINT ck_voice_analysis_session_times CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
        AND expires_at > created_at
    ),
    INDEX idx_voice_analysis_session_user_created (user_id, created_at),
    INDEX idx_voice_analysis_session_user_status (user_id, status),
    INDEX idx_voice_analysis_session_expiry (status, expires_at),
    INDEX idx_voice_analysis_session_song (song_id)
);

CREATE TABLE voice_range_measurement (
    range_measurement_id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    measurement_uid VARCHAR(100) NOT NULL,
    rule_version VARCHAR(50) NOT NULL,
    source VARCHAR(30) NOT NULL,
    full_low_midi DECIMAL(6,2) NULL,
    full_high_midi DECIMAL(6,2) NULL,
    note_results JSON NOT NULL,
    measurement_status VARCHAR(30) NOT NULL,
    quality_status VARCHAR(30) NOT NULL,
    measured_at DATETIME(6) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_range_measurement PRIMARY KEY (range_measurement_id),
    CONSTRAINT uk_voice_range_measurement_session UNIQUE (session_id),
    CONSTRAINT uk_voice_range_measurement_uid UNIQUE (measurement_uid),
    CONSTRAINT fk_voice_range_measurement_session
        FOREIGN KEY (session_id) REFERENCES voice_analysis_session (session_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_voice_range_measurement_status CHECK (
        measurement_status IN ('AVAILABLE', 'INSUFFICIENT_DATA', 'FAILED')
    ),
    CONSTRAINT ck_voice_range_measurement_source CHECK (source = 'ON_DEVICE'),
    CONSTRAINT ck_voice_range_measurement_quality CHECK (
        quality_status IN ('VALID', 'NOISY', 'CLIPPED', 'INSUFFICIENT_VOICE')
    ),
    CONSTRAINT ck_voice_range_measurement_full CHECK (
        full_low_midi IS NULL OR full_high_midi IS NULL OR full_low_midi <= full_high_midi
    )
);

CREATE TABLE voice_analysis_component_job (
    component_job_id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    component_type VARCHAR(30) NOT NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    ai_job_id VARCHAR(255) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    failure_code VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    model_key VARCHAR(50) NULL,
    model_version VARCHAR(100) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_analysis_component_job PRIMARY KEY (component_job_id),
    CONSTRAINT uk_voice_component_job_attempt
        UNIQUE (session_id, component_type, attempt_no),
    CONSTRAINT uk_voice_component_job_ai_job UNIQUE (ai_job_id),
    CONSTRAINT uk_voice_component_job_session UNIQUE (component_job_id, session_id),
    CONSTRAINT fk_voice_component_job_session
        FOREIGN KEY (session_id) REFERENCES voice_analysis_session (session_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_voice_component_job_type CHECK (
        component_type IN ('ECAPA', 'SINGER_BYOL', 'ARTIST_SEARCH')
    ),
    CONSTRAINT ck_voice_component_job_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_voice_component_job_status CHECK (
        status IN ('PENDING', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'INSUFFICIENT_DATA', 'CANCELED')
    ),
    CONSTRAINT ck_voice_component_job_times CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
    ),
    INDEX idx_voice_component_job_session_status (session_id, status),
    INDEX idx_voice_component_job_status_created (status, created_at)
);

CREATE TABLE voice_model_result (
    model_result_id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    component_job_id BIGINT NOT NULL,
    model_key VARCHAR(30) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    dimension INT NOT NULL,
    vector_object_key VARCHAR(1024) NOT NULL,
    vector_sha256 CHAR(64) NOT NULL,
    segment_info JSON NOT NULL,
    quality_metrics JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_model_result PRIMARY KEY (model_result_id),
    CONSTRAINT uk_voice_model_result_session_model UNIQUE (session_id, model_key),
    CONSTRAINT fk_voice_model_result_job_session
        FOREIGN KEY (component_job_id, session_id)
        REFERENCES voice_analysis_component_job (component_job_id, session_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_voice_model_result_contract CHECK (
        (model_key = 'ECAPA' AND dimension = 192)
        OR (model_key = 'SINGER_BYOL' AND dimension = 1000)
    ),
    INDEX idx_voice_model_result_job (component_job_id)
);

CREATE TABLE similar_artist_result (
    similar_artist_result_id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    component_job_id BIGINT NOT NULL,
    singer_id BIGINT NOT NULL,
    rank_no SMALLINT NOT NULL,
    cosine_similarity DECIMAL(8,6) NOT NULL,
    catalog_batch_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_similar_artist_result PRIMARY KEY (similar_artist_result_id),
    CONSTRAINT uk_similar_artist_result_singer UNIQUE (session_id, singer_id),
    CONSTRAINT uk_similar_artist_result_rank UNIQUE (session_id, rank_no),
    CONSTRAINT fk_similar_artist_result_job_session
        FOREIGN KEY (component_job_id, session_id)
        REFERENCES voice_analysis_component_job (component_job_id, session_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_similar_artist_result_singer
        FOREIGN KEY (singer_id) REFERENCES singer (singer_id),
    CONSTRAINT fk_similar_artist_result_catalog
        FOREIGN KEY (catalog_batch_id) REFERENCES embedding_catalog_batch (batch_id),
    CONSTRAINT ck_similar_artist_result_rank CHECK (rank_no BETWEEN 1 AND 5),
    CONSTRAINT ck_similar_artist_result_score CHECK (
        cosine_similarity BETWEEN -1.000000 AND 1.000000
    ),
    INDEX idx_similar_artist_result_job (component_job_id),
    INDEX idx_similar_artist_result_singer (singer_id)
);

CREATE TABLE user_voice_profile (
    profile_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    profile_version BIGINT NOT NULL DEFAULT 1,
    saved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_voice_profile PRIMARY KEY (profile_id),
    CONSTRAINT uk_user_voice_profile_user UNIQUE (user_id),
    CONSTRAINT uk_user_voice_profile_session UNIQUE (session_id),
    CONSTRAINT fk_user_voice_profile_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_voice_profile_session_owner
        FOREIGN KEY (session_id, user_id)
        REFERENCES voice_analysis_session (session_id, user_id),
    CONSTRAINT ck_user_voice_profile_version CHECK (profile_version >= 1)
);

CREATE TABLE voice_profile_save_job (
    save_job_id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    requested_result_version BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    profile_id BIGINT NULL,
    saved_profile_version BIGINT NULL,
    failure_code VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_profile_save_job PRIMARY KEY (save_job_id),
    CONSTRAINT uk_voice_profile_save_job_public UNIQUE (public_id),
    CONSTRAINT uk_voice_profile_save_job_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_voice_profile_save_job_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_profile_save_job_session_owner
        FOREIGN KEY (session_id, user_id)
        REFERENCES voice_analysis_session (session_id, user_id),
    CONSTRAINT fk_voice_profile_save_job_profile
        FOREIGN KEY (profile_id) REFERENCES user_voice_profile (profile_id)
        ON DELETE SET NULL,
    CONSTRAINT ck_voice_profile_save_job_version CHECK (requested_result_version >= 1),
    CONSTRAINT ck_voice_profile_save_job_status CHECK (
        status IN ('PENDING', 'SAVING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_voice_profile_save_job_saved_version CHECK (
        saved_profile_version IS NULL OR saved_profile_version >= 1
    ),
    CONSTRAINT ck_voice_profile_save_job_times CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
    ),
    INDEX idx_voice_profile_save_job_user_created (user_id, created_at),
    INDEX idx_voice_profile_save_job_status_created (status, created_at)
);

ALTER TABLE voice_recording
    ADD COLUMN session_id BIGINT NULL;

ALTER TABLE voice_recording
    ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'TIMBRE_ANALYSIS';

ALTER TABLE voice_recording
    ADD CONSTRAINT fk_voice_recording_session
    FOREIGN KEY (session_id) REFERENCES voice_analysis_session (session_id)
    ON DELETE SET NULL;

ALTER TABLE voice_recording
    ADD CONSTRAINT ck_voice_recording_purpose CHECK (
        purpose IN ('TIMBRE_ANALYSIS', 'PRACTICE_DETAIL')
    );

CREATE INDEX idx_voice_recording_session ON voice_recording (session_id);

CREATE TABLE voice_recording_context (
    recording_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    start_position_type VARCHAR(30) NOT NULL,
    selected_start_ms INT NOT NULL,
    actual_start_ms INT NOT NULL,
    accumulated_active_recording_ms INT NOT NULL,
    instrumental_version VARCHAR(100) NOT NULL,
    lyrics_version VARCHAR(100) NOT NULL,
    upload_idempotency_key VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_recording_context PRIMARY KEY (recording_id),
    CONSTRAINT uk_voice_recording_context_idempotency
        UNIQUE (session_id, upload_idempotency_key),
    CONSTRAINT fk_voice_recording_context_recording
        FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_voice_recording_context_session
        FOREIGN KEY (session_id) REFERENCES voice_analysis_session (session_id),
    CONSTRAINT fk_voice_recording_context_song
        FOREIGN KEY (song_id) REFERENCES song (song_id),
    CONSTRAINT ck_voice_recording_context_position CHECK (
        start_position_type IN ('BEGINNING', 'VERSE_2', 'HIGHLIGHT')
    ),
    CONSTRAINT ck_voice_recording_context_times CHECK (
        selected_start_ms >= 0
        AND actual_start_ms >= 0
        AND accumulated_active_recording_ms >= 30000
    ),
    INDEX idx_voice_recording_context_session (session_id)
);

CREATE TABLE voice_recording_segment (
    recording_segment_id BIGINT NOT NULL AUTO_INCREMENT,
    recording_id BIGINT NOT NULL,
    sequence_no SMALLINT NOT NULL,
    recording_start_ms INT NOT NULL,
    recording_end_ms INT NOT NULL,
    song_start_ms INT NOT NULL,
    song_end_ms INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_recording_segment PRIMARY KEY (recording_segment_id),
    CONSTRAINT uk_voice_recording_segment_sequence UNIQUE (recording_id, sequence_no),
    CONSTRAINT fk_voice_recording_segment_recording
        FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_voice_recording_segment_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_voice_recording_segment_recording_times CHECK (
        recording_start_ms >= 0 AND recording_end_ms > recording_start_ms
    ),
    CONSTRAINT ck_voice_recording_segment_song_times CHECK (
        song_start_ms >= 0 AND song_end_ms > song_start_ms
    )
);
