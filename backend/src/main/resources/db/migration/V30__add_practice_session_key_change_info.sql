ALTER TABLE practice_session
    ADD COLUMN initial_key SMALLINT NULL;

ALTER TABLE practice_session
    ADD COLUMN key_change_count INT UNSIGNED NOT NULL DEFAULT 0;

UPDATE practice_session
SET initial_key = selected_key
WHERE initial_key IS NULL;

ALTER TABLE practice_session
    MODIFY COLUMN initial_key SMALLINT NOT NULL;

ALTER TABLE practice_session
    ADD CONSTRAINT ck_practice_session_initial_key
    CHECK (initial_key BETWEEN -12 AND 12);
