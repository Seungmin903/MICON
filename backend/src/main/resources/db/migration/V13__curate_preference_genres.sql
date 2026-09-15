-- The ten selections come from the 890-track TJ inventory (2026-09-11).
-- Keep catalog genre IDs/labels intact: song imports still use normalized_name.
ALTER TABLE genre ADD COLUMN preference_label VARCHAR(100) NULL;
ALTER TABLE genre ADD COLUMN preference_order SMALLINT NULL;

INSERT INTO genre (name, normalized_name, category, preference_label, preference_order) VALUES
 ('발라드', 'kpop-ballad', 'KOREAN', '발라드', 1),
 ('댄스/팝', 'kpop-dance-pop', 'KOREAN', '댄스', 2),
 ('알앤비/소울', 'kpop-rnb-soul', 'KOREAN', 'R&B/소울', 3),
 ('락/메탈', 'kpop-rock-metal', 'KOREAN', '록/메탈', 4),
 ('랩/힙합', 'kpop-rap-hiphop', 'KOREAN', '힙합', 5),
 ('포크/어쿠스틱', 'kpop-folk-acoustic', 'KOREAN', '포크/어쿠스틱', 6),
 ('OST', 'etc-ost', 'ETC', 'OST', 7),
 ('팝', 'global-pop', 'INTERNATIONAL', '팝', 8),
 ('J-POP', 'etc-jpop', 'ETC', 'J-POP', 9),
 ('성인가요', 'kpop-adult', 'KOREAN', '트로트', 10)
ON DUPLICATE KEY UPDATE
 preference_label = VALUES(preference_label), preference_order = VALUES(preference_order);

CREATE INDEX idx_genre_preference_order ON genre (preference_order, genre_id);
