-- Local development only. Do not run against shared, staging, or production databases.
-- IDs in the 9,900,000 range are reserved here only to make this script rerunnable.

SET NAMES utf8mb4;
START TRANSACTION;

INSERT IGNORE INTO genre
    (genre_id, name, normalized_name, category, image_url, source_url)
VALUES
    (9900001, '로컬 발라드', 'local_seed_ballad', 'KOREAN', NULL, NULL),
    (9900002, '로컬 팝',     'local_seed_pop',     'KOREAN', NULL, NULL),
    (9900003, '로컬 록',     'local_seed_rock',    'KOREAN', NULL, NULL);

INSERT IGNORE INTO singer
    (singer_id, name, normalized_name, image_url, gender, type)
VALUES
    (9900001, '윤하늘', 'local_seed_yoon_haneul', NULL, 'FEMALE', 'SOLO'),
    (9900002, '김새벽', 'local_seed_kim_saebyeok', NULL, 'MALE', 'SOLO'),
    (9900003, '파란주파수', 'local_seed_blue_frequency', NULL, 'MIXED', 'BAND'),
    (9900004, '서린', 'local_seed_seorin', NULL, 'FEMALE', 'SOLO');

INSERT IGNORE INTO song
    (song_id, title, title_normalized, duration_ms, status, practice_ready)
VALUES
    (9900001, '별빛 아래',   '별빛 아래',   218000, 'ACTIVE', TRUE),
    (9900002, '다시, 봄',    '다시 봄',     201000, 'ACTIVE', TRUE),
    (9900003, '푸른 소음',   '푸른 소음',   244000, 'ACTIVE', TRUE),
    (9900004, '느린 편지',   '느린 편지',   232000, 'ACTIVE', FALSE),
    (9900005, '우리의 온도', '우리의 온도', 196000, 'ACTIVE', TRUE);

INSERT IGNORE INTO song_metadata
    (song_id, tj_number, catalog_track_id, album_name, release_date,
     display_image_url, primary_language, lyrics_license_status,
     lyrics_distribution_allowed, metadata_review_status)
VALUES
    (9900001, 'L00001', 'local-song-001', '밤의 기록', '2026-01-15',
     'https://placehold.co/512x512/24324a/ffffff?text=Local+01', 'ko',
     'UNVERIFIED', FALSE, 'APPROVED'),
    (9900002, 'L00002', 'local-song-002', '계절의 문', '2026-02-20',
     'https://placehold.co/512x512/adc9a7/ffffff?text=Local+02', 'ko',
     'UNVERIFIED', FALSE, 'APPROVED'),
    (9900003, 'L00003', 'local-song-003', '파동', '2026-03-12',
     'https://placehold.co/512x512/315c72/ffffff?text=Local+03', 'ko',
     'UNVERIFIED', FALSE, 'APPROVED'),
    (9900004, 'L00004', 'local-song-004', '보내지 못한 말', '2026-04-03',
     'https://placehold.co/512x512/a78374/ffffff?text=Local+04', 'ko',
     'UNVERIFIED', FALSE, 'APPROVED'),
    (9900005, 'L00005', 'local-song-005', '함께 걷는 밤', '2026-05-18',
     'https://placehold.co/512x512/c07f93/ffffff?text=Local+05', 'ko',
     'UNVERIFIED', FALSE, 'APPROVED');

INSERT IGNORE INTO song_singer
    (song_id, singer_id, role, display_order)
VALUES
    (9900001, 9900001, 'MAIN', 1),
    (9900002, 9900002, 'MAIN', 1),
    (9900003, 9900003, 'GROUP', 1),
    (9900004, 9900004, 'MAIN', 1),
    (9900005, 9900001, 'DUET', 1),
    (9900005, 9900002, 'DUET', 2);

INSERT IGNORE INTO song_genre
    (song_id, genre_id, is_primary)
VALUES
    (9900001, 9900001, TRUE),
    (9900002, 9900002, TRUE),
    (9900002, 9900001, FALSE),
    (9900003, 9900003, TRUE),
    (9900004, 9900001, TRUE),
    (9900005, 9900002, TRUE);

INSERT IGNORE INTO song_analysis
    (analysis_id, song_id, analysis_version, model, device, status,
     key_tonic, key_mode, key_name, key_confidence, tempo_bpm,
     lowest_midi, highest_midi, core_low_midi, core_high_midi,
     range_confidence, analyzed_at)
VALUES
    (9900001, 9900001, 'local-v1', 'LOCAL_DUMMY', 'CPU', 'COMPLETED',
     0, 'MAJOR', 'C Major', 0.95000, 76.000, 55.00, 79.00, 57.00, 76.00,
     0.92000, CURRENT_TIMESTAMP(6)),
    (9900002, 9900002, 'local-v1', 'LOCAL_DUMMY', 'CPU', 'COMPLETED',
     7, 'MAJOR', 'G Major', 0.93000, 104.000, 52.00, 76.00, 55.00, 74.00,
     0.90000, CURRENT_TIMESTAMP(6)),
    (9900003, 9900003, 'local-v1', 'LOCAL_DUMMY', 'CPU', 'COMPLETED',
     9, 'MINOR', 'A Minor', 0.91000, 138.000, 48.00, 74.00, 50.00, 72.00,
     0.88000, CURRENT_TIMESTAMP(6)),
    (9900004, 9900004, 'local-v1', 'LOCAL_DUMMY', 'CPU', 'COMPLETED',
     5, 'MAJOR', 'F Major', 0.94000, 68.000, 57.00, 81.00, 59.00, 78.00,
     0.91000, CURRENT_TIMESTAMP(6)),
    (9900005, 9900005, 'local-v1', 'LOCAL_DUMMY', 'CPU', 'COMPLETED',
     2, 'MAJOR', 'D Major', 0.92000, 112.000, 50.00, 78.00, 53.00, 75.00,
     0.89000, CURRENT_TIMESTAMP(6));

COMMIT;

SELECT s.song_id, s.title, GROUP_CONCAT(si.name ORDER BY ss.display_order SEPARATOR ', ') AS singers
FROM song s
LEFT JOIN song_singer ss ON ss.song_id = s.song_id
LEFT JOIN singer si ON si.singer_id = ss.singer_id
WHERE s.song_id BETWEEN 9900001 AND 9900005
GROUP BY s.song_id, s.title
ORDER BY s.song_id;
