CREATE TABLE voice_recording (
    recording_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    attempt_no SMALLINT NOT NULL DEFAULT 1,
    storage_path VARCHAR(1024) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    sha256 CHAR(64) NULL,
    file_bytes BIGINT NULL,
    duration_ms INT NULL,
    sample_rate INT NULL,
    keep_original BOOLEAN NOT NULL DEFAULT FALSE,
    upload_status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
    delete_scheduled_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_recording PRIMARY KEY (recording_id),
    CONSTRAINT fk_voice_recording_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT ck_voice_recording_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_voice_recording_upload_status CHECK (
        upload_status IN ('UPLOADING', 'UPLOADED', 'FAILED', 'DELETE_PENDING', 'DELETED')
    )
);

CREATE INDEX idx_voice_recording_user_created
    ON voice_recording (user_id, created_at);
CREATE INDEX idx_voice_recording_delete_schedule
    ON voice_recording (upload_status, delete_scheduled_at);

CREATE TABLE voice_analysis (
    voice_analysis_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    recording_id BIGINT NOT NULL,
    song_id BIGINT NULL,
    method VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'READY',
    failure_code VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    analysis_version VARCHAR(50) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    quality_status VARCHAR(30) NULL,
    CONSTRAINT pk_voice_analysis PRIMARY KEY (voice_analysis_id),
    CONSTRAINT fk_voice_analysis_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_voice_analysis_recording FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id),
    CONSTRAINT ck_voice_analysis_method CHECK (method IN ('SCALE', 'SONG')),
    CONSTRAINT ck_voice_analysis_status CHECK (
        status IN ('READY', 'RECORDING', 'UPLOADING', 'ANALYZING', 'COMPLETED', 'FAILED', 'CANCELED', 'INSUFFICIENT_DATA')
    ),
    CONSTRAINT ck_voice_analysis_quality_status CHECK (
        quality_status IS NULL OR quality_status IN ('VALID', 'NOISY', 'CLIPPED', 'INSUFFICIENT_VOICE')
    )
);

CREATE INDEX idx_voice_analysis_user_created
    ON voice_analysis (user_id, created_at);
CREATE INDEX idx_voice_analysis_recording
    ON voice_analysis (recording_id);
CREATE INDEX idx_voice_analysis_song
    ON voice_analysis (song_id);
CREATE INDEX idx_voice_analysis_status
    ON voice_analysis (status);

CREATE TABLE vocal_range_analysis (
    vocal_range_analysis_id BIGINT NOT NULL AUTO_INCREMENT,
    voice_analysis_id BIGINT NOT NULL,
    full_low_midi DECIMAL(6,2) NULL,
    full_high_midi DECIMAL(6,2) NULL,
    stable_low_midi DECIMAL(6,2) NULL,
    stable_high_midi DECIMAL(6,2) NULL,
    pitch_median_midi DECIMAL(6,2) NULL,
    pitch_accuracy_score DECIMAL(5,2) NULL,
    pitch_stability_score DECIMAL(5,2) NULL,
    pitch_distribution JSON NULL,
    modal_low_midi DECIMAL(6,2) NULL,
    modal_high_midi DECIMAL(6,2) NULL,
    falsetto_low_midi DECIMAL(6,2) NULL,
    falsetto_high_midi DECIMAL(6,2) NULL,
    high_note_hold_sec DECIMAL(6,2) NULL,
    confidence DECIMAL(5,2) NULL,
    note_results JSON NULL,
    measured_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_vocal_range_analysis PRIMARY KEY (vocal_range_analysis_id),
    CONSTRAINT uk_vocal_range_analysis_voice_analysis UNIQUE (voice_analysis_id),
    CONSTRAINT fk_vocal_range_analysis_voice_analysis
        FOREIGN KEY (voice_analysis_id) REFERENCES voice_analysis (voice_analysis_id)
);

CREATE TABLE vocal_timbre_analysis (
    vocal_timbre_analysis_id BIGINT NOT NULL AUTO_INCREMENT,
    voice_analysis_id BIGINT NOT NULL,
    timbre_label VARCHAR(100) NULL,
    timbre_vector JSON NULL,
    rhythm_score DECIMAL(5,2) NULL,
    pronunciation_score DECIMAL(5,2) NULL,
    confidence DECIMAL(5,2) NULL,
    explanation VARCHAR(1000) NULL,
    valid_single_sec DECIMAL(8,3) NULL,
    valid_chunk_count INT NULL,
    user_embedding JSON NULL,
    embedding_ref VARCHAR(1024) NULL,
    chunk_cohesion DECIMAL(6,2) NULL,
    observed_core_low_midi DECIMAL(6,2) NULL,
    observed_core_high_midi DECIMAL(6,2) NULL,
    observed_median_midi DECIMAL(6,2) NULL,
    pitch_histogram JSON NULL,
    measured_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_vocal_timbre_analysis PRIMARY KEY (vocal_timbre_analysis_id),
    CONSTRAINT uk_vocal_timbre_analysis_voice_analysis UNIQUE (voice_analysis_id),
    CONSTRAINT fk_vocal_timbre_analysis_voice_analysis
        FOREIGN KEY (voice_analysis_id) REFERENCES voice_analysis (voice_analysis_id)
);
