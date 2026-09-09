-- MICON complete schema
-- Target: MySQL 8.0.16+
-- Intended for an empty database. This script does not DROP existing objects.

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `micon`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `micon`;

-- 사용자
CREATE TABLE `users` (
    `user_id` BIGINT NOT NULL AUTO_INCREMENT,
    `email` VARCHAR(255) NOT NULL,
    `password` VARCHAR(255) NULL COMMENT '복구할 수 없는 비밀번호 해시만 저장',
    `nickname` VARCHAR(64) NULL,
    `auth_type` ENUM('EMAIL', 'SOCIAL') NOT NULL DEFAULT 'EMAIL',
    `email_verified` BOOLEAN NOT NULL DEFAULT FALSE,
    `status` ENUM('ACTIVE', 'SUSPENDED', 'WITHDRAWN') NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    `last_login_at` DATETIME(6) NULL,
    `withdrawn_at` DATETIME(6) NULL,

    CONSTRAINT `pk_users` PRIMARY KEY (`user_id`),
    CONSTRAINT `uk_users_email` UNIQUE (`email`)
);

-- 장르
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

-- 가수
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

-- 노래
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

-- 소셜 로그인
CREATE TABLE `social_login` (
    `social_login_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `provider` ENUM('GOOGLE', 'KAKAO', 'NAVER', 'APPLE') NOT NULL,
    `provider_user_id` VARCHAR(255) NOT NULL,
    `provider_email` VARCHAR(320) NULL,
    `connected_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `disconnected_at` DATETIME(6) NULL,

    CONSTRAINT `pk_social_login` PRIMARY KEY (`social_login_id`),
    CONSTRAINT `uk_social_login_provider_user` UNIQUE (`provider`, `provider_user_id`),
    CONSTRAINT `uk_social_login_user_provider` UNIQUE (`user_id`, `provider`),
    CONSTRAINT `fk_social_login_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
);

-- 리프레시 토큰
CREATE TABLE `refresh_token` (
    `refresh_token_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `session_id` VARCHAR(36) NOT NULL,
    `token_hash` VARCHAR(255) NOT NULL,
    `device_id` VARCHAR(100) NULL,
    `device_name` VARCHAR(100) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `expires_at` DATETIME(6) NOT NULL,
    `last_used_at` DATETIME(6) NULL,
    `revoked_at` DATETIME(6) NULL,

    CONSTRAINT `pk_refresh_token` PRIMARY KEY (`refresh_token_id`),
    CONSTRAINT `uk_refresh_token_session` UNIQUE (`session_id`),
    CONSTRAINT `uk_refresh_token_hash` UNIQUE (`token_hash`),
    CONSTRAINT `fk_refresh_token_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `ck_refresh_token_expiration`
        CHECK (`expires_at` > `created_at`),
    INDEX `idx_refresh_token_user` (`user_id`),
    INDEX `idx_refresh_token_expires_at` (`expires_at`)
);

-- 비밀번호 재설정 요청(기존 백엔드 마이그레이션 반영)
CREATE TABLE `password_reset_request` (
    `password_reset_request_id` BIGINT NOT NULL AUTO_INCREMENT,
    `request_id` VARCHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `code_hash` VARCHAR(255) NOT NULL,
    `attempt_count` INT NOT NULL DEFAULT 0,
    `expires_at` DATETIME(6) NOT NULL,
    `verified_at` DATETIME(6) NULL,
    `reset_token_hash` VARCHAR(64) NULL,
    `reset_token_expires_at` DATETIME(6) NULL,
    `used_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_password_reset_request` PRIMARY KEY (`password_reset_request_id`),
    CONSTRAINT `uk_password_reset_request_id` UNIQUE (`request_id`),
    CONSTRAINT `uk_password_reset_token_hash` UNIQUE (`reset_token_hash`),
    CONSTRAINT `fk_password_reset_request_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `ck_password_reset_attempt_count` CHECK (`attempt_count` >= 0),
    INDEX `idx_password_reset_request_user` (`user_id`),
    INDEX `idx_password_reset_request_expires_at` (`expires_at`)
);

-- 사용자 설정
CREATE TABLE `user_setting` (
    `user_id` BIGINT NOT NULL,
    `save_original_recording` BOOLEAN NOT NULL DEFAULT FALSE,
    `count_in_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `count_in_seconds` SMALLINT UNSIGNED NOT NULL DEFAULT 3,
    `vibration_enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_user_setting` PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_user_setting_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `ck_user_setting_count_in`
        CHECK (`count_in_seconds` BETWEEN 0 AND 10)
);

-- 노래 메타데이터
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

-- 곡 분석
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

-- 곡 음표 묶음
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

-- 노래 미디어/분석 산출물
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

-- 노래 구간
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

-- 노래-가수 관계
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

-- 노래-장르 관계
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

-- 사용자 선호 가수
CREATE TABLE `user_singer_preference` (
    `user_id` BIGINT NOT NULL,
    `singer_id` BIGINT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_user_singer_preference` PRIMARY KEY (`user_id`, `singer_id`),
    CONSTRAINT `fk_user_singer_preference_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_singer_preference_singer`
        FOREIGN KEY (`singer_id`) REFERENCES `singer` (`singer_id`) ON DELETE CASCADE
);

-- 사용자 선호 장르
CREATE TABLE `user_genre_preference` (
    `user_id` BIGINT NOT NULL,
    `genre_id` BIGINT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_user_genre_preference` PRIMARY KEY (`user_id`, `genre_id`),
    CONSTRAINT `fk_user_genre_preference_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_genre_preference_genre`
        FOREIGN KEY (`genre_id`) REFERENCES `genre` (`genre_id`) ON DELETE CASCADE
);

-- 찜한 노래
CREATE TABLE `favorite_song` (
    `user_id` BIGINT NOT NULL,
    `song_id` BIGINT NOT NULL,
    `preferred_key` SMALLINT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_favorite_song` PRIMARY KEY (`user_id`, `song_id`),
    CONSTRAINT `fk_favorite_song_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_favorite_song_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE
);

-- 세트리스트
CREATE TABLE `setlist` (
    `setlist_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_setlist` PRIMARY KEY (`setlist_id`),
    CONSTRAINT `fk_setlist_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE,
    INDEX `idx_setlist_user` (`user_id`)
);

-- 세트리스트 항목
CREATE TABLE `setlist_item` (
    `setlist_item_id` BIGINT NOT NULL AUTO_INCREMENT,
    `setlist_id` BIGINT NOT NULL,
    `song_id` BIGINT NOT NULL,
    `position_no` INT UNSIGNED NOT NULL,
    `added_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_setlist_item` PRIMARY KEY (`setlist_item_id`),
    CONSTRAINT `uk_setlist_item_song` UNIQUE (`setlist_id`, `song_id`),
    CONSTRAINT `uk_setlist_item_position` UNIQUE (`setlist_id`, `position_no`),
    CONSTRAINT `fk_setlist_item_setlist`
        FOREIGN KEY (`setlist_id`) REFERENCES `setlist` (`setlist_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_setlist_item_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_setlist_item_position` CHECK (`position_no` >= 1)
);

-- 음성 녹음
CREATE TABLE `voice_recording` (
    `recording_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `attempt_no` SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    `storage_path` VARCHAR(1024) NOT NULL,
    `media_type` VARCHAR(100) NOT NULL,
    `sha256` CHAR(64) NULL,
    `file_bytes` BIGINT UNSIGNED NULL,
    `duration_ms` INT UNSIGNED NULL,
    `sample_rate` INT UNSIGNED NULL,
    `keep_original` BOOLEAN NOT NULL DEFAULT FALSE,
    `upload_status` ENUM('UPLOADING', 'UPLOADED', 'FAILED', 'DELETE_PENDING', 'DELETED')
        NOT NULL DEFAULT 'UPLOADING',
    `delete_scheduled_at` DATETIME(6) NULL,
    `deleted_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_voice_recording` PRIMARY KEY (`recording_id`),
    CONSTRAINT `fk_voice_recording_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `ck_voice_recording_attempt_no` CHECK (`attempt_no` >= 1),
    CONSTRAINT `ck_voice_recording_sample_rate`
        CHECK (`sample_rate` IS NULL OR `sample_rate` > 0),
    INDEX `idx_voice_recording_user_created` (`user_id`, `created_at`),
    INDEX `idx_voice_recording_delete_schedule` (`upload_status`, `delete_scheduled_at`)
);

-- 음성 분석 실행
CREATE TABLE `voice_analysis` (
    `voice_analysis_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `recording_id` BIGINT NULL,
    `song_id` BIGINT NULL COMMENT 'method=SONG일 때 분석에 사용한 노래',
    `job_id` VARCHAR(255) NULL,
    `method` ENUM('SCALE', 'SONG') NOT NULL,
    `status` ENUM('READY', 'RECORDING', 'UPLOADING', 'ANALYZING', 'COMPLETED', 'FAILED', 'CANCELED', 'INSUFFICIENT_DATA')
        NOT NULL DEFAULT 'READY',
    `failure_code` VARCHAR(100) NULL,
    `failure_reason` VARCHAR(1000) NULL,
    `analysis_version` VARCHAR(50) NULL,
    `report_version` VARCHAR(50) NULL,
    `quality_status` ENUM('VALID', 'NOISY', 'CLIPPED', 'INSUFFICIENT_VOICE') NULL,
    `started_at` DATETIME(6) NULL,
    `completed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_voice_analysis` PRIMARY KEY (`voice_analysis_id`),
    CONSTRAINT `uk_voice_analysis_job_id` UNIQUE (`job_id`),
    CONSTRAINT `fk_voice_analysis_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `fk_voice_analysis_recording`
        FOREIGN KEY (`recording_id`) REFERENCES `voice_recording` (`recording_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_voice_analysis_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`),
    CONSTRAINT `ck_voice_analysis_times`
        CHECK (`started_at` IS NULL OR `completed_at` IS NULL OR `completed_at` >= `started_at`),
    INDEX `idx_voice_analysis_user_created` (`user_id`, `created_at`),
    INDEX `idx_voice_analysis_recording` (`recording_id`),
    INDEX `idx_voice_analysis_song` (`song_id`),
    INDEX `idx_voice_analysis_status` (`status`)
);

-- 사용자 음역 분석 결과
CREATE TABLE `vocal_range_analysis` (
    `vocal_range_analysis_id` BIGINT NOT NULL AUTO_INCREMENT,
    `voice_analysis_id` BIGINT NOT NULL,
    `full_low_midi` DECIMAL(6,2) NULL,
    `full_high_midi` DECIMAL(6,2) NULL,
    `stable_low_midi` DECIMAL(6,2) NULL,
    `stable_high_midi` DECIMAL(6,2) NULL,
    `pitch_median_midi` DECIMAL(6,2) NULL,
    `pitch_accuracy_score` DECIMAL(5,2) NULL,
    `pitch_stability_score` DECIMAL(5,2) NULL,
    `pitch_distribution` JSON NULL,
    `modal_low_midi` DECIMAL(6,2) NULL,
    `modal_high_midi` DECIMAL(6,2) NULL,
    `falsetto_low_midi` DECIMAL(6,2) NULL,
    `falsetto_high_midi` DECIMAL(6,2) NULL,
    `high_note_hold_sec` DECIMAL(8,3) NULL,
    `confidence` DECIMAL(6,5) NULL,
    `note_results` JSON NULL,
    `measured_at` DATETIME(6) NOT NULL,

    CONSTRAINT `pk_vocal_range_analysis` PRIMARY KEY (`vocal_range_analysis_id`),
    CONSTRAINT `uk_vocal_range_analysis_voice` UNIQUE (`voice_analysis_id`),
    CONSTRAINT `fk_vocal_range_analysis_voice`
        FOREIGN KEY (`voice_analysis_id`) REFERENCES `voice_analysis` (`voice_analysis_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_vocal_range_full`
        CHECK (`full_low_midi` IS NULL OR `full_high_midi` IS NULL OR `full_high_midi` >= `full_low_midi`),
    CONSTRAINT `ck_vocal_range_stable`
        CHECK (`stable_low_midi` IS NULL OR `stable_high_midi` IS NULL OR `stable_high_midi` >= `stable_low_midi`),
    CONSTRAINT `ck_vocal_range_modal`
        CHECK (`modal_low_midi` IS NULL OR `modal_high_midi` IS NULL OR `modal_high_midi` >= `modal_low_midi`),
    CONSTRAINT `ck_vocal_range_falsetto`
        CHECK (`falsetto_low_midi` IS NULL OR `falsetto_high_midi` IS NULL OR `falsetto_high_midi` >= `falsetto_low_midi`),
    CONSTRAINT `ck_vocal_range_confidence`
        CHECK (`confidence` IS NULL OR `confidence` BETWEEN 0 AND 1)
);

-- 사용자 음색 분석 결과
CREATE TABLE `vocal_timbre_analysis` (
    `vocal_timbre_analysis_id` BIGINT NOT NULL AUTO_INCREMENT,
    `voice_analysis_id` BIGINT NOT NULL,
    `timbre_label` VARCHAR(100) NULL,
    `timbre_vector` JSON NULL,
    `rhythm_score` DECIMAL(5,2) NULL,
    `pronunciation_score` DECIMAL(5,2) NULL,
    `confidence` DECIMAL(6,5) NULL,
    `explanation` VARCHAR(1000) NULL,
    `valid_singing_sec` DECIMAL(8,3) NULL,
    `valid_chunk_count` INT UNSIGNED NULL,
    `user_embedding` JSON NULL,
    `embedding_ref` VARCHAR(1024) NULL,
    `chunk_cohesion` DECIMAL(6,5) NULL,
    `audio_quality_status` ENUM('VALID', 'NOISY', 'CLIPPED', 'INSUFFICIENT_VOICE') NULL,
    `observed_core_low_midi` DECIMAL(6,2) NULL,
    `observed_core_high_midi` DECIMAL(6,2) NULL,
    `observed_median_midi` DECIMAL(6,2) NULL,
    `pitch_histogram` JSON NULL,
    `embedding_model_version` VARCHAR(50) NULL,
    `preprocessing_version` VARCHAR(50) NULL,
    `measured_at` DATETIME(6) NOT NULL,

    CONSTRAINT `pk_vocal_timbre_analysis` PRIMARY KEY (`vocal_timbre_analysis_id`),
    CONSTRAINT `uk_vocal_timbre_analysis_voice` UNIQUE (`voice_analysis_id`),
    CONSTRAINT `fk_vocal_timbre_analysis_voice`
        FOREIGN KEY (`voice_analysis_id`) REFERENCES `voice_analysis` (`voice_analysis_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_vocal_timbre_confidence`
        CHECK (`confidence` IS NULL OR `confidence` BETWEEN 0 AND 1),
    CONSTRAINT `ck_vocal_timbre_cohesion`
        CHECK (`chunk_cohesion` IS NULL OR `chunk_cohesion` BETWEEN 0 AND 1),
    CONSTRAINT `ck_vocal_timbre_observed_range`
        CHECK (`observed_core_low_midi` IS NULL OR `observed_core_high_midi` IS NULL OR `observed_core_high_midi` >= `observed_core_low_midi`)
);

-- 노래 연습 세션
CREATE TABLE `practice_session` (
    `practice_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `song_id` BIGINT NOT NULL,
    `section_id` BIGINT NULL,
    `recording_id` BIGINT NULL,
    `voice_analysis_id` BIGINT NULL COMMENT '추천 키 산정에 사용한 사용자 음성 분석',
    `practice_scope` ENUM('FULL_SONG', 'SECTION', 'CUSTOM') NOT NULL DEFAULT 'FULL_SONG',
    `selected_key` SMALLINT NOT NULL DEFAULT 0,
    `playback_speed` DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    `custom_start_ms` INT UNSIGNED NULL,
    `custom_end_ms` INT UNSIGNED NULL,
    `status` ENUM('READY', 'IN_PROGRESS', 'PAUSED', 'COMPLETED', 'ABORTED', 'FAILED')
        NOT NULL DEFAULT 'READY',
    `failure_code` VARCHAR(100) NULL,
    `failure_reason` VARCHAR(1000) NULL,
    `started_at` DATETIME(6) NULL,
    `ended_at` DATETIME(6) NULL,
    `duration_ms` INT UNSIGNED NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_practice_session` PRIMARY KEY (`practice_id`),
    CONSTRAINT `fk_practice_session_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `fk_practice_session_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`),
    CONSTRAINT `fk_practice_session_section`
        FOREIGN KEY (`section_id`) REFERENCES `song_section` (`section_id`),
    CONSTRAINT `fk_practice_session_recording`
        FOREIGN KEY (`recording_id`) REFERENCES `voice_recording` (`recording_id`) ON DELETE SET NULL,
    CONSTRAINT `fk_practice_session_voice_analysis`
        FOREIGN KEY (`voice_analysis_id`) REFERENCES `voice_analysis` (`voice_analysis_id`) ON DELETE SET NULL,
    CONSTRAINT `ck_practice_session_speed` CHECK (`playback_speed` > 0),
    CONSTRAINT `ck_practice_session_times`
        CHECK (`started_at` IS NULL OR `ended_at` IS NULL OR `ended_at` >= `started_at`),
    CONSTRAINT `ck_practice_session_scope`
        CHECK (
            (`practice_scope` = 'FULL_SONG' AND `section_id` IS NULL AND `custom_start_ms` IS NULL AND `custom_end_ms` IS NULL)
            OR (`practice_scope` = 'SECTION' AND `section_id` IS NOT NULL AND `custom_start_ms` IS NULL AND `custom_end_ms` IS NULL)
            OR (`practice_scope` = 'CUSTOM' AND `section_id` IS NULL AND `custom_start_ms` IS NOT NULL AND `custom_end_ms` IS NOT NULL AND `custom_end_ms` > `custom_start_ms`)
        ),
    INDEX `idx_practice_session_user_created` (`user_id`, `created_at`),
    INDEX `idx_practice_session_song` (`song_id`)
);

-- 연습 결과
CREATE TABLE `practice_result` (
    `practice_result_id` BIGINT NOT NULL AUTO_INCREMENT,
    `practice_id` BIGINT NOT NULL,
    `status` ENUM('ANALYZING', 'COMPLETED', 'INSUFFICIENT_DATA', 'FAILED')
        NOT NULL DEFAULT 'ANALYZING',
    `overall_score` DECIMAL(5,2) NULL,
    `pitch_score` DECIMAL(5,2) NULL,
    `sustain_score` DECIMAL(5,2) NULL,
    `rhythm_score` DECIMAL(5,2) NULL,
    `summary_text` VARCHAR(1000) NULL,
    `analyzed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT `pk_practice_result` PRIMARY KEY (`practice_result_id`),
    CONSTRAINT `uk_practice_result_practice` UNIQUE (`practice_id`),
    CONSTRAINT `fk_practice_result_practice`
        FOREIGN KEY (`practice_id`) REFERENCES `practice_session` (`practice_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_practice_result_overall`
        CHECK (`overall_score` IS NULL OR `overall_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_practice_result_pitch`
        CHECK (`pitch_score` IS NULL OR `pitch_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_practice_result_sustain`
        CHECK (`sustain_score` IS NULL OR `sustain_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_practice_result_rhythm`
        CHECK (`rhythm_score` IS NULL OR `rhythm_score` BETWEEN 0 AND 100)
);

-- 추천 실행 및 AI 작업 상태
CREATE TABLE `recommendation` (
    `recommendation_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `voice_analysis_id` BIGINT NOT NULL,
    `job_id` VARCHAR(255) NULL,
    `mode` ENUM('OVERALL', 'RANGE', 'TIMBRE', 'GENRE', 'POPULAR') NOT NULL,
    `status` ENUM('GENERATING', 'COMPLETED', 'EMPTY', 'FAILED')
        NOT NULL DEFAULT 'GENERATING',
    `progress` SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    `failure_code` VARCHAR(100) NULL,
    `failure_reason` VARCHAR(1000) NULL,
    `algorithm_version` VARCHAR(50) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `completed_at` DATETIME(6) NULL,
    `expires_at` DATETIME(6) NULL,

    CONSTRAINT `pk_recommendation` PRIMARY KEY (`recommendation_id`),
    CONSTRAINT `uk_recommendation_job_id` UNIQUE (`job_id`),
    CONSTRAINT `fk_recommendation_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    CONSTRAINT `fk_recommendation_voice_analysis`
        FOREIGN KEY (`voice_analysis_id`) REFERENCES `voice_analysis` (`voice_analysis_id`),
    CONSTRAINT `ck_recommendation_progress`
        CHECK (`progress` BETWEEN 0 AND 100),
    CONSTRAINT `ck_recommendation_completed_at`
        CHECK (`completed_at` IS NULL OR `completed_at` >= `created_at`),
    CONSTRAINT `ck_recommendation_expires_at`
        CHECK (`expires_at` IS NULL OR `expires_at` > `created_at`),
    INDEX `idx_recommendation_user_created` (`user_id`, `created_at`),
    INDEX `idx_recommendation_voice_analysis` (`voice_analysis_id`),
    INDEX `idx_recommendation_status` (`status`)
);

-- 추천 섹션(shelves[])
CREATE TABLE `recommendation_shelf` (
    `recommendation_id` BIGINT NOT NULL,
    `shelf_key` VARCHAR(50) NOT NULL,
    `title` VARCHAR(100) NOT NULL,
    `metric` VARCHAR(50) NULL,
    `shelf_order` INT UNSIGNED NOT NULL,

    CONSTRAINT `pk_recommendation_shelf`
        PRIMARY KEY (`recommendation_id`, `shelf_key`),
    CONSTRAINT `uk_recommendation_shelf_order`
        UNIQUE (`recommendation_id`, `shelf_order`),
    CONSTRAINT `fk_recommendation_shelf_recommendation`
        FOREIGN KEY (`recommendation_id`)
        REFERENCES `recommendation` (`recommendation_id`)
        ON DELETE CASCADE,
    CONSTRAINT `ck_recommendation_shelf_order`
        CHECK (`shelf_order` >= 1)
);

-- 추천 섹션에 포함된 추천 노래
CREATE TABLE `recommendation_song` (
    `recommendation_id` BIGINT NOT NULL,
    `song_id` BIGINT NOT NULL,
    `shelf_key` VARCHAR(50) NOT NULL,
    `rank_no` INT UNSIGNED NOT NULL,
    `shelf_position` INT UNSIGNED NOT NULL,
    `axis` VARCHAR(50) NULL,
    `label` VARCHAR(100) NULL,
    `recommended_key` SMALLINT NULL,
    `overall_score` DECIMAL(5,2) NULL,
    `range_fit_score` DECIMAL(5,2) NULL,
    `timbre_fit_score` DECIMAL(5,2) NULL,
    `rhythm_fit_score` DECIMAL(5,2) NULL,
    `range_fit` JSON NULL,
    `metrics` JSON NULL,
    `reason_text` VARCHAR(1000) NOT NULL,
    `scoring_version` VARCHAR(50) NULL,
    `composition_version` VARCHAR(50) NULL,

    CONSTRAINT `pk_recommendation_song` PRIMARY KEY (`recommendation_id`, `song_id`),
    CONSTRAINT `uk_recommendation_song_rank` UNIQUE (`recommendation_id`, `rank_no`),
    CONSTRAINT `uk_recommendation_song_shelf_position`
        UNIQUE (`recommendation_id`, `shelf_key`, `shelf_position`),
    CONSTRAINT `fk_recommendation_song_recommendation`
        FOREIGN KEY (`recommendation_id`) REFERENCES `recommendation` (`recommendation_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_recommendation_song_shelf`
        FOREIGN KEY (`recommendation_id`, `shelf_key`)
        REFERENCES `recommendation_shelf` (`recommendation_id`, `shelf_key`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_recommendation_song_song`
        FOREIGN KEY (`song_id`) REFERENCES `song` (`song_id`),
    CONSTRAINT `ck_recommendation_song_rank` CHECK (`rank_no` BETWEEN 1 AND 50),
    CONSTRAINT `ck_recommendation_song_shelf_position` CHECK (`shelf_position` >= 1),
    CONSTRAINT `ck_recommendation_song_overall`
        CHECK (`overall_score` IS NULL OR `overall_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_recommendation_song_range`
        CHECK (`range_fit_score` IS NULL OR `range_fit_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_recommendation_song_timbre`
        CHECK (`timbre_fit_score` IS NULL OR `timbre_fit_score` BETWEEN 0 AND 100),
    CONSTRAINT `ck_recommendation_song_rhythm`
        CHECK (`rhythm_fit_score` IS NULL OR `rhythm_fit_score` BETWEEN 0 AND 100)
);
