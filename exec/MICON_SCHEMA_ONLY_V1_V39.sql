-- MICON schema-only snapshot generated from Flyway V1..V39 in origin/develop (596be2f24441f79210fce24a975cbd64e0394512).
-- Intended for a NEW, EMPTY MySQL 8.4.11 database only. Not a production data dump.
-- All INSERT/UPDATE/DELETE/REPLACE statements (including reference-data seeds and user-row backfills) are intentionally omitted.
-- For deployed databases, use Flyway migrations and never run this snapshot over an existing schema.
CREATE DATABASE IF NOT EXISTS `micon`;
USE `micon`;

-- Source migration: V1__create_auth_tables.sql
CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NULL,
    auth_type VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_login_at DATETIME(6) NULL,
    withdrawn_at DATETIME(6) NULL,
    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_auth_type CHECK (auth_type IN ('EMAIL', 'SOCIAL')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'))
);
CREATE TABLE refresh_token (
    refresh_token_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    device_id VARCHAR(100) NULL,
    device_name VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT pk_refresh_token PRIMARY KEY (refresh_token_id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uk_refresh_token_session UNIQUE (session_id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_token_user_id ON refresh_token (user_id);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token (expires_at);

-- Source migration: V2__create_password_reset_requests.sql
CREATE TABLE password_reset_request (
    password_reset_request_id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6) NULL,
    reset_token_hash VARCHAR(64) NULL,
    reset_token_expires_at DATETIME(6) NULL,
    used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_password_reset_request PRIMARY KEY (password_reset_request_id),
    CONSTRAINT fk_password_reset_request_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uk_password_reset_request_id UNIQUE (request_id),
    CONSTRAINT uk_password_reset_token_hash UNIQUE (reset_token_hash),
    CONSTRAINT ck_password_reset_attempt_count CHECK (attempt_count >= 0)
);
CREATE INDEX idx_password_reset_request_user_id ON password_reset_request (user_id);
CREATE INDEX idx_password_reset_request_expires_at ON password_reset_request (expires_at);

-- Source migration: V3__create_voice_analysis_tables.sql
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

-- Source migration: V4__add_job_id_to_voice_analysis.sql
ALTER TABLE voice_analysis
    ADD COLUMN job_id VARCHAR(255) NULL;
ALTER TABLE voice_analysis
    ADD CONSTRAINT uk_voice_analysis_job_id UNIQUE (job_id);

-- Source migration: V5__create_recommendation_tables.sql
CREATE TABLE recommendation (
    recommendation_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    voice_analysis_id BIGINT NOT NULL,
    job_id VARCHAR(255) NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'OVERALL',
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATING',
    progress SMALLINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    algorithm_version VARCHAR(50) NULL,
    scoring_version VARCHAR(50) NULL,
    composition_version VARCHAR(50) NULL,
    generated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    CONSTRAINT pk_recommendation PRIMARY KEY (recommendation_id),
    CONSTRAINT fk_recommendation_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_recommendation_voice_analysis
        FOREIGN KEY (voice_analysis_id) REFERENCES voice_analysis (voice_analysis_id),
    CONSTRAINT uk_recommendation_job UNIQUE (job_id),
    CONSTRAINT ck_recommendation_mode CHECK (mode IN ('OVERALL', 'RANGE', 'TIMBRE', 'GENRE', 'POPULAR')),
    CONSTRAINT ck_recommendation_status CHECK (status IN ('GENERATING', 'COMPLETED', 'EMPTY', 'FAILED')),
    CONSTRAINT ck_recommendation_progress CHECK (progress BETWEEN 0 AND 100)
);
CREATE INDEX idx_recommendation_user_generated
    ON recommendation (user_id, generated_at);
CREATE INDEX idx_recommendation_voice_analysis
    ON recommendation (voice_analysis_id);
CREATE INDEX idx_recommendation_status
    ON recommendation (status);
CREATE TABLE recommendation_shelf (
    recommendation_shelf_id BIGINT NOT NULL AUTO_INCREMENT,
    recommendation_id BIGINT NOT NULL,
    shelf_key VARCHAR(50) NOT NULL,
    title VARCHAR(100) NOT NULL,
    metric VARCHAR(50) NULL,
    shelf_order SMALLINT NOT NULL,
    CONSTRAINT pk_recommendation_shelf PRIMARY KEY (recommendation_shelf_id),
    CONSTRAINT fk_recommendation_shelf_recommendation
        FOREIGN KEY (recommendation_id) REFERENCES recommendation (recommendation_id) ON DELETE CASCADE,
    CONSTRAINT uk_recommendation_shelf_key UNIQUE (recommendation_id, shelf_key),
    CONSTRAINT uk_recommendation_shelf_order UNIQUE (recommendation_id, shelf_order),
    CONSTRAINT uk_recommendation_shelf_parent UNIQUE (recommendation_id, recommendation_shelf_id),
    CONSTRAINT ck_recommendation_shelf_order CHECK (shelf_order > 0)
);
CREATE TABLE recommendation_song (
    recommendation_id BIGINT NOT NULL,
    song_id BIGINT NOT NULL,
    recommendation_shelf_id BIGINT NOT NULL,
    overall_rank SMALLINT NOT NULL,
    shelf_position SMALLINT NOT NULL,
    axis VARCHAR(50) NULL,
    label VARCHAR(100) NULL,
    recommended_key SMALLINT NULL,
    final_score DECIMAL(8,6) NULL,
    timbre_score DECIMAL(8,6) NULL,
    range_fit JSON NULL,
    metrics JSON NULL,
    reason_text VARCHAR(1000) NULL,
    scoring_version VARCHAR(50) NULL,
    composition_version VARCHAR(50) NULL,
    CONSTRAINT pk_recommendation_song PRIMARY KEY (recommendation_id, song_id),
    CONSTRAINT fk_recommendation_song_recommendation
        FOREIGN KEY (recommendation_id) REFERENCES recommendation (recommendation_id) ON DELETE CASCADE,
    CONSTRAINT fk_recommendation_song_shelf
        FOREIGN KEY (recommendation_id, recommendation_shelf_id)
        REFERENCES recommendation_shelf (recommendation_id, recommendation_shelf_id) ON DELETE CASCADE,
    CONSTRAINT uk_recommendation_song_rank UNIQUE (recommendation_id, overall_rank),
    CONSTRAINT uk_recommendation_song_shelf_position UNIQUE (recommendation_shelf_id, shelf_position),
    CONSTRAINT ck_recommendation_song_id CHECK (song_id > 0),
    CONSTRAINT ck_recommendation_song_rank CHECK (overall_rank BETWEEN 1 AND 50),
    CONSTRAINT ck_recommendation_song_shelf_position CHECK (shelf_position > 0)
);
-- song_id remains a BIGINT without a foreign key until the SONG migration owned by S15P21B110-149 is available.

-- Source migration: V6__create_song_data_tables.sql
-- S15P21B110-219: catalog tables, aligned with MICON_COMPLETE_DDL.sql.
-- Recommendation V5 is retained unchanged; the conflicting, unapplied song V5
-- is replaced by V6. Do not apply the full reference DDL to a Flyway-managed DB.

CREATE TABLE `genre` (
    `genre_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `normalized_name` VARCHAR(100) NOT NULL,
    `category` ENUM('KOREAN', 'INTERNATIONAL', 'ETC') NOT NULL,
    `image_url` VARCHAR(2048) NULL,
    `source_url` VARCHAR(2048) NULL COMMENT '장르 분류 출처 페이지',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_genre` PRIMARY KEY (`genre_id`),
    CONSTRAINT `uk_genre_normalized_name` UNIQUE (`normalized_name`),
    INDEX `idx_genre_category` (`category`)
);
CREATE TABLE `singer` (
    `singer_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `normalized_name` VARCHAR(100) NOT NULL,
    `image_url` VARCHAR(2048) NULL,
    `gender` ENUM('MALE', 'FEMALE', 'MIXED', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    `type` ENUM('SOLO', 'DUO', 'GROUP', 'BAND', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_singer` PRIMARY KEY (`singer_id`),
    CONSTRAINT `uk_singer_normalized_name` UNIQUE (`normalized_name`)
);
CREATE TABLE `song` (
    `song_id` BIGINT NOT NULL AUTO_INCREMENT,
    `title` VARCHAR(255) NOT NULL,
    `title_normalized` VARCHAR(255) NULL,
    `duration_ms` INT UNSIGNED NULL,
    `status` ENUM('PENDING', 'ACTIVE', 'INACTIVE', 'DELETED') NOT NULL DEFAULT 'PENDING',
    `practice_ready` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song` PRIMARY KEY (`song_id`),
    INDEX `idx_song_title_normalized` (`title_normalized`),
    INDEX `idx_song_status_ready` (`status`, `practice_ready`)
);
CREATE TABLE `song_metadata` (
    `song_id` BIGINT NOT NULL,
    `tj_number` VARCHAR(20) NULL,
    `track_id` VARCHAR(100) NULL COMMENT '외부 음원 제공처 트랙 ID',
    `catalog_track_id` VARCHAR(100) NULL COMMENT '추천/분석 카탈로그 조인 ID',
    `external_ids` JSON NULL,
    `credits` JSON NULL COMMENT '작곡·작사·저자 등 원본 크레딧',
    `album_name` VARCHAR(255) NULL,
    `release_date` DATE NULL,
    `release_date_raw` VARCHAR(50) NULL,
    `display_image_url` VARCHAR(2048) NULL,
    `primary_language` VARCHAR(10) NULL,
    `languages` JSON NULL,
    `source_genres` JSON NULL COMMENT '원본 카탈로그 장르명',
    `market_context_tags` JSON NULL,
    `chart_entries` JSON NULL,
    `lyrics_plain` MEDIUMTEXT NULL,
    `lyrics_synced` JSON NULL,
    `lyrics_source` VARCHAR(100) NULL,
    `lyrics_license_status` ENUM('UNVERIFIED', 'LICENSED', 'PUBLIC_DOMAIN', 'RESTRICTED')
        NOT NULL DEFAULT 'UNVERIFIED',
    `lyrics_distribution_allowed` BOOLEAN NOT NULL DEFAULT FALSE,
    `image_type` ENUM('ALBUM_COVER', 'VIDEO_THUMBNAIL', 'ARTIST_IMAGE', 'OTHER') NULL,
    `metadata_review_status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'NEEDS_REVIEW')
        NOT NULL DEFAULT 'PENDING',
    `source_url` VARCHAR(2048) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_metadata` PRIMARY KEY (`song_id`),
    CONSTRAINT `uk_song_metadata_tj_number` UNIQUE (`tj_number`),
    CONSTRAINT `uk_song_metadata_track_id` UNIQUE (`track_id`),
    CONSTRAINT `uk_song_metadata_catalog_track_id` UNIQUE (`catalog_track_id`),
    CONSTRAINT `fk_song_metadata_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE
);
CREATE TABLE `song_analysis` (
    `analysis_id` BIGINT NOT NULL AUTO_INCREMENT,
    `song_id` BIGINT NOT NULL,
    `analysis_version` VARCHAR(50) NOT NULL,
    `model` VARCHAR(500) NULL,
    `device` VARCHAR(50) NULL,
    `status` ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL', 'FAILED')
        NOT NULL DEFAULT 'PENDING',
    `error_code` VARCHAR(100) NULL,
    `error_message` TEXT NULL,
    `key_tonic` TINYINT UNSIGNED NULL,
    `key_mode` ENUM('MAJOR', 'MINOR', 'UNKNOWN') NULL,
    `key_name` VARCHAR(30) NULL,
    `key_confidence` DECIMAL(6,5) NULL,
    `tuning_cents` DECIMAL(7,2) NULL,
    `tempo_bpm` DECIMAL(7,3) NULL,
    `tempo_raw_bpm` DECIMAL(7,3) NULL,
    `tempo_candidates` JSON NULL,
    `time_signature` VARCHAR(10) NULL,
    `meter_confidence` DECIMAL(6,5) NULL,
    `lowest_midi` DECIMAL(6,2) NULL,
    `highest_midi` DECIMAL(6,2) NULL,
    `core_low_midi` DECIMAL(6,2) NULL,
    `core_high_midi` DECIMAL(6,2) NULL,
    `range_ratios` JSON NULL,
    `vocal_start_sec` DECIMAL(10,3) NULL,
    `vocal_end_sec` DECIMAL(10,3) NULL,
    `voiced_seconds` DECIMAL(10,3) NULL,
    `voiced_ratio` DECIMAL(6,5) NULL,
    `note_count` INT UNSIGNED NULL,
    `range_confidence` DECIMAL(6,5) NULL,
    `f0_agreement` DECIMAL(6,5) NULL,
    `octave_mismatch` DECIMAL(6,5) NULL,
    `task_support` JSON NULL,
    `task_pitch` VARCHAR(20) NULL,
    `register_summary` JSON NULL,
    `lyrics_alignment` JSON NULL,
    `karaoke_stats` JSON NULL,
    `separation_summary` JSON NULL,
    `beat_grid` JSON NULL,
    `score_octave_shift` SMALLINT NULL,
    `timings` JSON NULL,
    `provenance` JSON NULL,
    `analyzed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_analysis` PRIMARY KEY (`analysis_id`),
    CONSTRAINT `uk_song_analysis_song` UNIQUE (`song_id`),
    CONSTRAINT `fk_song_analysis_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_song_analysis_key_tonic`
        CHECK (`key_tonic` IS NULL OR `key_tonic` BETWEEN 0 AND 11),
    CONSTRAINT `ck_song_analysis_range`
        CHECK (`lowest_midi` IS NULL OR `highest_midi` IS NULL OR `highest_midi` >= `lowest_midi`),
    CONSTRAINT `ck_song_analysis_core_range`
        CHECK (`core_low_midi` IS NULL OR `core_high_midi` IS NULL OR `core_high_midi` >= `core_low_midi`),
    CONSTRAINT `ck_song_analysis_vocal_time`
        CHECK (`vocal_start_sec` IS NULL OR `vocal_end_sec` IS NULL OR `vocal_end_sec` >= `vocal_start_sec`),
    CONSTRAINT `ck_song_analysis_voiced_ratio`
        CHECK (`voiced_ratio` IS NULL OR `voiced_ratio` BETWEEN 0 AND 1),
    CONSTRAINT `ck_song_analysis_range_confidence`
        CHECK (`range_confidence` IS NULL OR `range_confidence` BETWEEN 0 AND 1),
    CONSTRAINT `ck_song_analysis_f0_agreement`
        CHECK (`f0_agreement` IS NULL OR `f0_agreement` BETWEEN 0 AND 1),
    CONSTRAINT `ck_song_analysis_octave_mismatch`
        CHECK (`octave_mismatch` IS NULL OR `octave_mismatch` BETWEEN 0 AND 1),
    INDEX `idx_song_analysis_key_name` (`key_name`),
    INDEX `idx_song_analysis_range` (`lowest_midi`, `highest_midi`),
    INDEX `idx_song_analysis_tempo` (`tempo_bpm`)
);
CREATE TABLE `song_note` (
    `song_note_id` BIGINT NOT NULL AUTO_INCREMENT,
    `analysis_id` BIGINT NOT NULL,
    `karaoke_notes` JSON NOT NULL,
    `source_notes` JSON NULL,
    `karaoke_version` VARCHAR(50) NOT NULL,
    `karaoke_note_count` INT UNSIGNED NOT NULL,
    `source_note_count` INT UNSIGNED NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_note` PRIMARY KEY (`song_note_id`),
    CONSTRAINT `uk_song_note_analysis` UNIQUE (`analysis_id`),
    CONSTRAINT `fk_song_note_analysis`
        FOREIGN KEY (`analysis_id`) REFERENCES `song_analysis` (`analysis_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_song_note_karaoke_count` CHECK (`karaoke_note_count` >= 0),
    CONSTRAINT `ck_song_note_source_count`
        CHECK (`source_note_count` IS NULL OR `source_note_count` >= 0)
);
CREATE TABLE `song_media` (
    `media_id` BIGINT NOT NULL AUTO_INCREMENT,
    `song_id` BIGINT NOT NULL,
    `kind` ENUM(
        'SOURCE_AUDIO', 'INSTRUMENTAL', 'INSTRUMENTAL_KEY', 'PITCH_FRAMES',
        'KARAOKE_MIDI', 'VOCAL_MIDI', 'KARAOKE_MUSICXML', 'VOCAL_MUSICXML'
    ) NOT NULL,
    `semitones` SMALLINT NOT NULL DEFAULT 0,
    `file_path` VARCHAR(1024) NOT NULL,
    `mime_type` VARCHAR(100) NULL,
    `file_bytes` BIGINT UNSIGNED NULL,
    `sha256` CHAR(64) NULL,
    `generation_status` ENUM('READY', 'GENERATING', 'MISSING', 'FAILED', 'REGENERATABLE')
        NOT NULL DEFAULT 'READY',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_media` PRIMARY KEY (`media_id`),
    CONSTRAINT `uk_song_media_variant` UNIQUE (`song_id`, `kind`, `semitones`),
    CONSTRAINT `fk_song_media_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE
);
CREATE TABLE `song_section` (
    `section_id` BIGINT NOT NULL AUTO_INCREMENT,
    `song_id` BIGINT NOT NULL,
    `analysis_id` BIGINT NULL,
    `section_type` ENUM(
        'INTRO', 'VOCAL', 'INTERLUDE', 'VERSE', 'PRE_CHORUS',
        'CHORUS', 'BRIDGE', 'OUTRO', 'CUSTOM'
    )
        NOT NULL,
    `section_name` VARCHAR(100) NOT NULL,
    `start_ms` INT UNSIGNED NOT NULL,
    `end_ms` INT UNSIGNED NOT NULL,
    `sequence_no` SMALLINT UNSIGNED NOT NULL,
    `difficulty_type` ENUM('NONE', 'HIGH_NOTE', 'LONG_BREATH', 'FAST_LYRICS', 'PITCH_JUMP', 'MIXED')
        NOT NULL DEFAULT 'NONE',
    `difficulty_score` DECIMAL(5,2) NULL,
    `confidence` DECIMAL(6,5) NULL,
    `description` VARCHAR(500) NULL,
    `source_type` ENUM('MANUAL', 'AI', 'DERIVED', 'EXTERNAL') NOT NULL DEFAULT 'AI',
    `needs_review` BOOLEAN NOT NULL DEFAULT FALSE,
    `review_reasons` JSON NULL,
    `source_metadata` JSON NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_section` PRIMARY KEY (`section_id`),
    CONSTRAINT `uk_song_section_sequence` UNIQUE (`song_id`, `sequence_no`),
    CONSTRAINT `fk_song_section_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_song_section_analysis`
        FOREIGN KEY (`analysis_id`) REFERENCES `song_analysis` (`analysis_id`) ON DELETE SET NULL,
    CONSTRAINT `ck_song_section_time` CHECK (`end_ms` > `start_ms`),
    CONSTRAINT `ck_song_section_difficulty`
        CHECK (`difficulty_score` IS NULL OR `difficulty_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_song_section_confidence`
        CHECK (`confidence` IS NULL OR `confidence` BETWEEN 0 AND 1)
);
CREATE TABLE `song_singer` (
    `song_id` BIGINT NOT NULL,
    `singer_id` BIGINT NOT NULL,
    `role` ENUM('MAIN', 'FEATURED', 'DUET', 'GROUP') NOT NULL DEFAULT 'MAIN',
    `display_order` SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_singer` PRIMARY KEY (`song_id`, `singer_id`),
    CONSTRAINT `fk_song_singer_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_song_singer_singer`
        FOREIGN KEY (`singer_id`) REFERENCES `singer` (`singer_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_song_singer_display_order` CHECK (`display_order` >= 1)
);
CREATE TABLE `song_genre` (
    `song_id` BIGINT NOT NULL,
    `genre_id` BIGINT NOT NULL,
    `is_primary` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_song_genre` PRIMARY KEY (`song_id`, `genre_id`),
    CONSTRAINT `fk_song_genre_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_song_genre_genre`
        FOREIGN KEY (`genre_id`) REFERENCES `genre` (`genre_id`) ON DELETE CASCADE,
    INDEX `idx_song_genre_genre` (`genre_id`)
);

-- Source migration: V7__create_embedding_catalog.sql
-- S15P21B110-227. Immutable validated batches; vectors remain in private S3.
CREATE TABLE embedding_catalog_batch (
    batch_id VARCHAR(64) NOT NULL,
    bucket VARCHAR(63) NOT NULL,
    region VARCHAR(32) NOT NULL,
    object_prefix VARCHAR(512) NOT NULL,
    manifest_key VARCHAR(1024) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    manifest_bytes INT NOT NULL,
    models_json JSON NOT NULL,
    track_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_embedding_catalog_batch PRIMARY KEY (batch_id),
    CONSTRAINT ck_embedding_batch_counts CHECK (track_count > 0 AND manifest_bytes > 0)
);
CREATE TABLE song_embedding (
    batch_id VARCHAR(64) NOT NULL,
    song_id BIGINT NOT NULL,
    catalog_track_id VARCHAR(100) NOT NULL,
    model_key VARCHAR(32) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    file_bytes INT NOT NULL,
    segment_count SMALLINT NOT NULL,
    dimension SMALLINT NOT NULL,
    CONSTRAINT pk_song_embedding PRIMARY KEY (batch_id, song_id, model_key),
    CONSTRAINT uk_song_embedding_track UNIQUE (batch_id, catalog_track_id, model_key),
    CONSTRAINT fk_song_embedding_batch FOREIGN KEY (batch_id)
        REFERENCES embedding_catalog_batch (batch_id),
    CONSTRAINT fk_song_embedding_song FOREIGN KEY (song_id) REFERENCES song (song_id),
    CONSTRAINT ck_song_embedding_model CHECK (
        (model_key = 'ecapa' AND dimension = 192) OR
        (model_key = 'singer_byol' AND dimension = 1000)),
    CONSTRAINT ck_song_embedding_size CHECK (
        file_bytes > 0 AND segment_count BETWEEN 1 AND 9),
    INDEX idx_song_embedding_song (song_id)
);
CREATE TABLE embedding_catalog_active (
    channel VARCHAR(32) NOT NULL,
    batch_id VARCHAR(64) NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_embedding_catalog_active PRIMARY KEY (channel),
    CONSTRAINT fk_embedding_active_batch FOREIGN KEY (batch_id)
        REFERENCES embedding_catalog_batch (batch_id),
    CONSTRAINT ck_embedding_active_revision CHECK (revision >= 0)
);
CREATE TABLE embedding_catalog_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    channel VARCHAR(32) NOT NULL,
    previous_batch_id VARCHAR(64) NULL,
    batch_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    actor VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_embedding_catalog_event PRIMARY KEY (event_id),
    CONSTRAINT uk_embedding_event_revision UNIQUE (channel, revision),
    CONSTRAINT fk_embedding_event_batch FOREIGN KEY (batch_id)
        REFERENCES embedding_catalog_batch (batch_id),
    CONSTRAINT fk_embedding_event_previous FOREIGN KEY (previous_batch_id)
        REFERENCES embedding_catalog_batch (batch_id),
    CONSTRAINT ck_embedding_event_revision CHECK (revision > 0)
);

-- Source migration: V8__create_user_music_preferences.sql
CREATE TABLE user_singer_preference (
    user_id BIGINT NOT NULL,
    singer_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_singer_preference PRIMARY KEY (user_id, singer_id),
    CONSTRAINT fk_user_singer_preference_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_singer_preference_singer
        FOREIGN KEY (singer_id) REFERENCES singer (singer_id) ON DELETE CASCADE
);
CREATE TABLE user_genre_preference (
    user_id BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_genre_preference PRIMARY KEY (user_id, genre_id),
    CONSTRAINT fk_user_genre_preference_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_genre_preference_genre
        FOREIGN KEY (genre_id) REFERENCES genre (genre_id) ON DELETE CASCADE
);

-- Source migration: V9__allow_fifty_songs_per_recommendation_shelf.sql
ALTER TABLE recommendation_song DROP CONSTRAINT ck_recommendation_song_rank;
ALTER TABLE recommendation_song
    ADD CONSTRAINT ck_recommendation_song_rank CHECK (overall_rank > 0);
ALTER TABLE recommendation_song DROP CONSTRAINT ck_recommendation_song_shelf_position;
ALTER TABLE recommendation_song
    ADD CONSTRAINT ck_recommendation_song_shelf_position CHECK (shelf_position BETWEEN 1 AND 50);
ALTER TABLE recommendation_shelf MODIFY COLUMN metric VARCHAR(255) NULL;

-- Source migration: V10__add_vocal_stem_song_media_kind.sql
ALTER TABLE song_media MODIFY COLUMN kind ENUM(
    'SOURCE_AUDIO', 'INSTRUMENTAL', 'INSTRUMENTAL_KEY', 'VOCAL_STEM', 'PITCH_FRAMES',
    'KARAOKE_MIDI', 'VOCAL_MIDI', 'KARAOKE_MUSICXML', 'VOCAL_MUSICXML'
) NOT NULL;

-- Source migration: V11__complete_song_reference_constraints.sql
-- Complete logical song references that could not be declared before the
-- song catalog tables were introduced by V6. Existing columns are preserved.

ALTER TABLE voice_analysis
    ADD CONSTRAINT fk_voice_analysis_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE voice_analysis
    ADD CONSTRAINT ck_voice_analysis_times
        CHECK (started_at IS NULL OR completed_at IS NULL OR completed_at >= started_at);
ALTER TABLE recommendation_song
    ADD CONSTRAINT fk_recommendation_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE recommendation
    ADD CONSTRAINT ck_recommendation_completed_at
        CHECK (completed_at IS NULL OR completed_at >= generated_at);
ALTER TABLE recommendation
    ADD CONSTRAINT ck_recommendation_expiration
        CHECK (expires_at IS NULL OR expires_at > generated_at);
ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_full
        CHECK (full_low_midi IS NULL OR full_high_midi IS NULL OR full_high_midi >= full_low_midi);
ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_stable
        CHECK (stable_low_midi IS NULL OR stable_high_midi IS NULL OR stable_high_midi >= stable_low_midi);
ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_modal
        CHECK (modal_low_midi IS NULL OR modal_high_midi IS NULL OR modal_high_midi >= modal_low_midi);
ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_falsetto
        CHECK (falsetto_low_midi IS NULL OR falsetto_high_midi IS NULL OR falsetto_high_midi >= falsetto_low_midi);
ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_high_note_hold
        CHECK (high_note_hold_sec IS NULL OR high_note_hold_sec >= 0);
ALTER TABLE vocal_timbre_analysis
    ADD CONSTRAINT ck_vocal_timbre_valid_single_sec
        CHECK (valid_single_sec IS NULL OR valid_single_sec >= 0);
ALTER TABLE vocal_timbre_analysis
    ADD CONSTRAINT ck_vocal_timbre_valid_chunk_count
        CHECK (valid_chunk_count IS NULL OR valid_chunk_count >= 0);
ALTER TABLE vocal_timbre_analysis
    ADD CONSTRAINT ck_vocal_timbre_observed_range
        CHECK (
            observed_core_low_midi IS NULL
            OR observed_core_high_midi IS NULL
            OR observed_core_high_midi >= observed_core_low_midi
        );

-- Source migration: V12__create_user_collection_and_practice_tables.sql
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

-- Source migration: V13__curate_preference_genres.sql
-- The ten selections come from the 890-track TJ inventory (2026-09-11).
-- Keep catalog genre IDs/labels intact: song imports still use normalized_name.
ALTER TABLE genre ADD COLUMN preference_label VARCHAR(100) NULL;
ALTER TABLE genre ADD COLUMN preference_order SMALLINT NULL;
CREATE INDEX idx_genre_preference_order ON genre (preference_order, genre_id);

-- Source migration: V14__add_artist_portraits.sql
-- Reviewed artist photos; album covers are never used as portraits.
-- Source, author and license are served to the app with every curated image.
-- Name aliases do not create singers or change their IDs / recommendation identity.
CREATE TABLE artist_portrait (
 normalized_name VARCHAR(100) NOT NULL PRIMARY KEY,
 display_name VARCHAR(100) NOT NULL,
 search_names VARCHAR(500) NOT NULL,
 image_url VARCHAR(2048) NOT NULL,
 source_url VARCHAR(2048) NOT NULL,
 attribution VARCHAR(2000) NOT NULL,
 license VARCHAR(100) NOT NULL,
 license_url VARCHAR(2048) NULL
);

-- Source migration: V15__label_foreign_language_preferences.sql

-- Source migration: V16__add_recording_practice_status.sql
ALTER TABLE practice_session
    MODIFY COLUMN status ENUM(
        'READY', 'RECORDING', 'IN_PROGRESS', 'PAUSED',
        'COMPLETED', 'ABORTED', 'FAILED'
    ) NOT NULL DEFAULT 'READY';

-- Source migration: V17__create_voice_analysis_session_tables.sql
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

-- Source migration: V18__replace_legacy_voice_analysis_relations.sql
-- Replace relationships that depended on the legacy, single-result voice analysis model.
--
-- recommendation.profile_id is intentionally nullable during this migration. Existing
-- recommendation rows cannot be mapped automatically because the legacy analysis rows
-- do not identify a saved v4 profile. New application writes must always provide it.

ALTER TABLE recommendation
    ADD COLUMN profile_id BIGINT NULL;
ALTER TABLE recommendation
    ADD CONSTRAINT fk_recommendation_voice_profile
        FOREIGN KEY (profile_id) REFERENCES user_voice_profile (profile_id);
CREATE INDEX idx_recommendation_profile
    ON recommendation (profile_id);
ALTER TABLE recommendation
    DROP FOREIGN KEY fk_recommendation_voice_analysis;
DROP INDEX idx_recommendation_voice_analysis
    ON recommendation;
ALTER TABLE recommendation
    DROP COLUMN voice_analysis_id;
-- Practice evaluation owns its result through practice_result. It no longer points
-- at an onboarding voice analysis.
ALTER TABLE practice_session
    DROP FOREIGN KEY fk_practice_session_voice_analysis;
DROP INDEX idx_practice_session_voice_analysis
    ON practice_session;
ALTER TABLE practice_session
    DROP COLUMN voice_analysis_id;
-- Remove the legacy result tables from child to parent after every external FK has
-- been detached. The v4 replacements are created by V17.
DROP TABLE vocal_timbre_analysis;
DROP TABLE vocal_range_analysis;
DROP TABLE voice_analysis;

-- Source migration: V19__support_direct_voice_analysis_handoff.sql
-- Store metadata and analysis results without retaining the uploaded raw audio.
ALTER TABLE voice_recording
    MODIFY COLUMN storage_path VARCHAR(1024) NULL;
ALTER TABLE voice_recording
    ADD COLUMN storage_mode VARCHAR(20) NOT NULL DEFAULT 'STORED' AFTER attempt_no;
ALTER TABLE voice_recording
    ADD CONSTRAINT ck_voice_recording_storage_mode CHECK (
        storage_mode IN ('STORED', 'NOT_STORED')
    );
ALTER TABLE voice_recording
    ADD CONSTRAINT ck_voice_recording_storage_path CHECK (
        (storage_mode = 'STORED' AND storage_path IS NOT NULL)
        OR (storage_mode = 'NOT_STORED' AND storage_path IS NULL)
    );
ALTER TABLE voice_analysis_component_job
    ADD COLUMN request_id VARCHAR(100) NULL AFTER attempt_no;
ALTER TABLE voice_analysis_component_job
    ADD COLUMN recording_id BIGINT NULL AFTER session_id;
ALTER TABLE voice_analysis_component_job
    ADD CONSTRAINT uk_voice_component_job_request UNIQUE (request_id);
ALTER TABLE voice_analysis_component_job
    ADD CONSTRAINT fk_voice_component_job_recording
        FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id)
        ON DELETE SET NULL;
CREATE INDEX idx_voice_component_job_recording
    ON voice_analysis_component_job (recording_id);
ALTER TABLE voice_model_result
    MODIFY COLUMN vector_object_key VARCHAR(1024) NULL;
ALTER TABLE voice_model_result
    ADD COLUMN vector_json JSON NULL AFTER dimension;
ALTER TABLE voice_model_result
    ADD CONSTRAINT ck_voice_model_result_vector CHECK (
        (vector_json IS NOT NULL AND vector_object_key IS NULL)
        OR (vector_json IS NULL AND vector_object_key IS NOT NULL)
    );

-- Source migration: V20__create_voice_recording_deletion_queue.sql
-- Persist deletion work so an object-store outage or application restart cannot lose it.
CREATE TABLE voice_recording_deletion_job (
    deletion_job_id BIGINT NOT NULL AUTO_INCREMENT,
    recording_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_recording_deletion_job PRIMARY KEY (deletion_job_id),
    CONSTRAINT uk_voice_recording_deletion_recording UNIQUE (recording_id),
    CONSTRAINT fk_voice_recording_deletion_recording
        FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_voice_recording_deletion_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'FAILED', 'COMPLETED')
    ),
    CONSTRAINT ck_voice_recording_deletion_attempt CHECK (attempt_count >= 0),
    INDEX idx_voice_recording_deletion_retry (status, next_attempt_at)
);

-- Source migration: V21__support_server_range_analysis.sql
-- Range recordings are forwarded to AI and only their metadata/results are retained by BE.
ALTER TABLE voice_range_measurement
    DROP CONSTRAINT ck_voice_range_measurement_source;
ALTER TABLE voice_range_measurement
    ADD CONSTRAINT ck_voice_range_measurement_source CHECK (source = 'SERVER_AI');
ALTER TABLE voice_analysis_component_job
    DROP CONSTRAINT ck_voice_component_job_type;
ALTER TABLE voice_analysis_component_job
    ADD CONSTRAINT ck_voice_component_job_type CHECK (
        component_type IN ('RANGE', 'ECAPA', 'SINGER_BYOL', 'ARTIST_SEARCH')
    );
ALTER TABLE voice_analysis_component_job
    DROP CONSTRAINT ck_voice_component_job_status;
ALTER TABLE voice_analysis_component_job
    ADD CONSTRAINT ck_voice_component_job_status CHECK (
        status IN ('PENDING', 'DISPATCH_UNKNOWN', 'QUEUED', 'RUNNING', 'COMPLETED',
                   'FAILED', 'INSUFFICIENT_DATA', 'CANCELED')
    );

-- Source migration: V22__create_parent_voice_analysis_job.sql
CREATE TABLE voice_analysis_job (
 analysis_job_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, session_id BIGINT NOT NULL,
 recording_id BIGINT NULL, request_id VARCHAR(100) NOT NULL UNIQUE, ai_job_id VARCHAR(255) NULL UNIQUE,
 analysis_type VARCHAR(30) NOT NULL, attempt_no INT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 failure_code VARCHAR(100) NULL, failure_reason VARCHAR(1000) NULL, started_at DATETIME(6) NULL,
 completed_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY (session_id) REFERENCES voice_analysis_session(session_id) ON DELETE CASCADE,
 FOREIGN KEY (recording_id) REFERENCES voice_recording(recording_id) ON DELETE SET NULL,
 CHECK (analysis_type='TIMBRE'), CHECK(status IN('PENDING','DISPATCH_UNKNOWN','QUEUED','RUNNING','COMPLETED','FAILED','CANCELED'))
);
ALTER TABLE voice_analysis_component_job ADD COLUMN analysis_job_id BIGINT NULL AFTER component_job_id;
ALTER TABLE voice_analysis_component_job ADD CONSTRAINT fk_voice_component_parent FOREIGN KEY(analysis_job_id) REFERENCES voice_analysis_job(analysis_job_id) ON DELETE CASCADE;
ALTER TABLE voice_analysis_component_job ADD CONSTRAINT uk_voice_component_parent_type UNIQUE(analysis_job_id,component_type);

-- Source migration: V23__extend_parent_voice_analysis_job_contract.sql
-- Extend the parent job created by V22 without recreating tables owned by the range/session work.
ALTER TABLE voice_analysis_job
    ADD COLUMN audio_sha256 CHAR(64) NULL AFTER request_id;
ALTER TABLE voice_analysis_job
    ADD COLUMN audio_expires_at DATETIME(6) NULL AFTER status;
ALTER TABLE voice_analysis_job
    ADD COLUMN duplicate BOOLEAN NOT NULL DEFAULT FALSE AFTER audio_expires_at;
CREATE INDEX idx_voice_analysis_job_recording
    ON voice_analysis_job (recording_id);
CREATE INDEX idx_voice_analysis_job_status_created
    ON voice_analysis_job (status, created_at);

-- Source migration: V24__allow_device_range_results.sql
-- The Android guided-range-v7 flow evaluates pitch on-device and submits the verified
-- result into the same session used by the server timbre analysis.
ALTER TABLE voice_range_measurement
    DROP CONSTRAINT ck_voice_range_measurement_source;
ALTER TABLE voice_range_measurement
    ADD CONSTRAINT ck_voice_range_measurement_source CHECK (source IN ('ON_DEVICE', 'SERVER_AI'));

-- Source migration: V25__separate_preference_onboarding_status.sql
ALTER TABLE user_setting
    ADD COLUMN preference_onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE
    AFTER save_original_recording;

-- Source migration: V26__extend_similar_artist_results.sql
ALTER TABLE similar_artist_result
    DROP CONSTRAINT ck_similar_artist_result_rank;
ALTER TABLE voice_analysis_component_job
    ADD COLUMN requested_result_version BIGINT NULL;
ALTER TABLE voice_analysis_component_job
    ADD COLUMN requested_top_k INT NULL;
ALTER TABLE voice_analysis_component_job
    ADD COLUMN catalog_version VARCHAR(64) NULL;
ALTER TABLE voice_analysis_component_job
    ADD COLUMN scoring_version VARCHAR(100) NULL;
ALTER TABLE similar_artist_result
    ADD COLUMN ecapa_score DECIMAL(8,6) NULL;
ALTER TABLE similar_artist_result
    ADD COLUMN singer_byol_score DECIMAL(8,6) NULL;
ALTER TABLE similar_artist_result
    ADD CONSTRAINT ck_similar_artist_result_rank
    CHECK (rank_no BETWEEN 1 AND 10);
ALTER TABLE similar_artist_result
    ADD CONSTRAINT ck_similar_artist_result_ecapa_score
    CHECK (ecapa_score IS NULL OR ecapa_score BETWEEN -1.000000 AND 1.000000);
ALTER TABLE similar_artist_result
    ADD CONSTRAINT ck_similar_artist_result_singer_byol_score CHECK (
        singer_byol_score IS NULL OR singer_byol_score BETWEEN -1.000000 AND 1.000000
    );

-- Source migration: V27__allow_server_range_processing_statuses.sql
-- Server-side range analysis uses transient upload and analysis states before
-- the final AVAILABLE / INSUFFICIENT_DATA / FAILED result is persisted.
ALTER TABLE voice_analysis_session
    DROP CONSTRAINT ck_voice_analysis_session_range_status;
ALTER TABLE voice_analysis_session
    ADD CONSTRAINT ck_voice_analysis_session_range_status CHECK (
        range_status IN (
            'PENDING', 'IN_PROGRESS', 'UPLOADING', 'ANALYZING',
            'AVAILABLE', 'INSUFFICIENT_DATA', 'FAILED'
        )
    );

-- Source migration: V28__convert_song_id_to_string.sql
-- Convert the catalog primary key and every referencing column to a stable string identifier.
-- Existing numeric identifiers are preserved as their decimal string representation.

ALTER TABLE recommendation_song DROP FOREIGN KEY fk_recommendation_song_song;
ALTER TABLE song_metadata DROP FOREIGN KEY fk_song_metadata_song;
ALTER TABLE song_analysis DROP FOREIGN KEY fk_song_analysis_song;
ALTER TABLE song_media DROP FOREIGN KEY fk_song_media_song;
ALTER TABLE song_section DROP FOREIGN KEY fk_song_section_song;
ALTER TABLE song_singer DROP FOREIGN KEY fk_song_singer_song;
ALTER TABLE song_genre DROP FOREIGN KEY fk_song_genre_song;
ALTER TABLE song_embedding DROP FOREIGN KEY fk_song_embedding_song;
ALTER TABLE favorite_song DROP FOREIGN KEY fk_favorite_song_song;
ALTER TABLE setlist_item DROP FOREIGN KEY fk_setlist_item_song;
ALTER TABLE practice_session DROP FOREIGN KEY fk_practice_session_song;
ALTER TABLE voice_analysis_session DROP FOREIGN KEY fk_voice_analysis_session_song;
ALTER TABLE voice_recording_context DROP FOREIGN KEY fk_voice_recording_context_song;
ALTER TABLE recommendation_song DROP CONSTRAINT ck_recommendation_song_id;
ALTER TABLE song MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE recommendation_song MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_metadata MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_analysis MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_media MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_section MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_singer MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_genre MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_embedding MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE favorite_song MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE setlist_item MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE practice_session MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE voice_analysis_session MODIFY COLUMN song_id VARCHAR(64) NULL;
ALTER TABLE voice_recording_context MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE recommendation_song
    ADD CONSTRAINT fk_recommendation_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE song_metadata
    ADD CONSTRAINT fk_song_metadata_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_analysis
    ADD CONSTRAINT fk_song_analysis_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_media
    ADD CONSTRAINT fk_song_media_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_section
    ADD CONSTRAINT fk_song_section_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_singer
    ADD CONSTRAINT fk_song_singer_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_genre
    ADD CONSTRAINT fk_song_genre_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_embedding
    ADD CONSTRAINT fk_song_embedding_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE favorite_song
    ADD CONSTRAINT fk_favorite_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE setlist_item
    ADD CONSTRAINT fk_setlist_item_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE practice_session
    ADD CONSTRAINT fk_practice_session_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE voice_analysis_session
    ADD CONSTRAINT fk_voice_analysis_session_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE SET NULL;
ALTER TABLE voice_recording_context
    ADD CONSTRAINT fk_voice_recording_context_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);

-- Source migration: V29__snapshot_recommendation_profile_identity.sql
-- Do not backfill from mutable profiles: legacy recommendations may belong to
-- an earlier session. NULL identity deliberately excludes them from latest/fit.
ALTER TABLE recommendation
    ADD COLUMN source_session_id BIGINT NULL;
ALTER TABLE recommendation ADD COLUMN profile_version BIGINT NULL;
CREATE INDEX idx_recommendation_profile_snapshot
    ON recommendation (profile_id, profile_version, source_session_id, status);

-- Source migration: V30__add_practice_session_key_change_info.sql
ALTER TABLE practice_session
    ADD COLUMN initial_key SMALLINT NULL;
ALTER TABLE practice_session
    ADD COLUMN key_change_count INT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE practice_session
    MODIFY COLUMN initial_key SMALLINT NOT NULL;
ALTER TABLE practice_session
    ADD CONSTRAINT ck_practice_session_initial_key
    CHECK (initial_key BETWEEN -12 AND 12);

-- Source migration: V31__create_social_identity_table.sql
CREATE TABLE social_identity (
    social_identity_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_social_identity PRIMARY KEY (social_identity_id),
    CONSTRAINT fk_social_identity_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT uk_social_identity_provider_subject UNIQUE (provider, provider_subject)
);

-- Source migration: V32__add_stable_range_bounds.sql
ALTER TABLE voice_range_measurement
    ADD COLUMN stable_low_midi DECIMAL(6,2) NULL;
ALTER TABLE voice_range_measurement
    ADD COLUMN stable_high_midi DECIMAL(6,2) NULL;

-- Source migration: V33__optimize_song_catalog_queries.sql
-- Empty catalog pages are repeatedly read by the Android recommendation screen.
-- These indexes cover its deterministic title ordering and batched artist display order.
CREATE INDEX idx_song_catalog_title ON song (title, song_id);
CREATE INDEX idx_song_singer_display_order ON song_singer (song_id, display_order, singer_id);

-- Source migration: V34__snapshot_recommendation_preference_version.sql
ALTER TABLE user_setting
    ADD COLUMN preference_version BIGINT NOT NULL DEFAULT 0
    AFTER preference_onboarding_completed;
-- NULL deliberately denotes recommendations created before preference snapshots.
ALTER TABLE recommendation
    ADD COLUMN preference_version BIGINT NULL
    AFTER profile_version;
CREATE INDEX idx_recommendation_preference_snapshot
    ON recommendation (user_id, preference_version, status);

-- Source migration: V35__optimize_song_genre_filter.sql
-- Genre-filtered catalog pages start from this access path.
CREATE INDEX idx_song_genre_genre_song ON song_genre (genre_id, song_id);

-- Source migration: V36__expand_practice_report_detail_storage.sql
ALTER TABLE practice_result
    MODIFY COLUMN summary_text LONGTEXT NULL;

-- Source migration: V37__retry_full_recommendation_queue.sql
ALTER TABLE recommendation
    ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE recommendation
    ADD COLUMN dispatch_retry_at DATETIME(6) NULL;
CREATE INDEX idx_recommendation_dispatch_retry
    ON recommendation (status, job_id, dispatch_retry_at, generated_at);

-- Source migration: V38__add_practice_score_trend_index.sql
CREATE INDEX idx_practice_session_user_song_created
    ON practice_session (user_id, song_id, created_at);

-- Source migration: V39__store_on_device_voice_quality.sql
ALTER TABLE voice_analysis_component_job
    ADD COLUMN voice_quality_json JSON NULL;
ALTER TABLE voice_range_measurement
    ADD COLUMN voice_quality_json JSON NULL;
