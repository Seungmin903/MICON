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
