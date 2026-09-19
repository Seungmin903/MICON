-- Finalize the 2026-09-19 timbre-tj975-ready898-v1 incident repair.
-- Run as a database administrator only after the BE/AI active-subset release is deployed.
-- The procedure restores the immutable S3 source count to 898 and advances the
-- active-set revision from 3 to 4. Any unexpected state aborts and rolls back.

DELIMITER $$

DROP PROCEDURE IF EXISTS finalize_active_catalog_875$$
CREATE PROCEDURE finalize_active_catalog_875()
BEGIN
    DECLARE active_batch VARCHAR(64);
    DECLARE active_revision BIGINT;
    DECLARE source_track_count INT;
    DECLARE active_song_count INT;
    DECLARE embedding_song_count INT;
    DECLARE active_embedding_song_count INT;
    DECLARE catalog_track_count INT;
    DECLARE embedding_row_count INT;
    DECLARE ecapa_row_count INT;
    DECLARE singer_byol_row_count INT;
    DECLARE orphan_row_count INT;
    DECLARE inactive_embedding_row_count INT;
    DECLARE invalid_model_group_count INT;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    SELECT batch_id, revision
      INTO active_batch, active_revision
    FROM embedding_catalog_active
    WHERE channel = 'default'
    FOR UPDATE;

    SELECT track_count
      INTO source_track_count
    FROM embedding_catalog_batch
    WHERE batch_id = 'timbre-tj975-ready898-v1'
    FOR UPDATE;

    SELECT COUNT(*)
      INTO active_song_count
    FROM song
    WHERE status = 'ACTIVE';

    SELECT COUNT(DISTINCT se.song_id),
           COUNT(DISTINCT CASE WHEN s.status = 'ACTIVE' THEN se.song_id END),
           COUNT(DISTINCT se.catalog_track_id),
           COUNT(*),
           COALESCE(SUM(se.model_key = 'ecapa'), 0),
           COALESCE(SUM(se.model_key = 'singer_byol'), 0),
           COALESCE(SUM(s.song_id IS NULL), 0),
           COALESCE(SUM(s.song_id IS NOT NULL AND s.status <> 'ACTIVE'), 0)
      INTO embedding_song_count,
           active_embedding_song_count,
           catalog_track_count,
           embedding_row_count,
           ecapa_row_count,
           singer_byol_row_count,
           orphan_row_count,
           inactive_embedding_row_count
    FROM song_embedding se
    LEFT JOIN song s ON s.song_id = se.song_id
    WHERE se.batch_id = 'timbre-tj975-ready898-v1';

    SELECT COUNT(*)
      INTO invalid_model_group_count
    FROM (
        SELECT se.song_id, se.catalog_track_id
        FROM song_embedding se
        WHERE se.batch_id = 'timbre-tj975-ready898-v1'
        GROUP BY se.song_id, se.catalog_track_id
        HAVING COUNT(*) <> 2
            OR COUNT(DISTINCT se.model_key) <> 2
            OR SUM(se.model_key = 'ecapa') <> 1
            OR SUM(se.model_key = 'singer_byol') <> 1
    ) invalid_groups;

    IF active_batch <> 'timbre-tj975-ready898-v1'
       OR active_revision <> 3
       OR source_track_count NOT IN (875, 898)
       OR active_song_count <> 875
       OR embedding_song_count <> 875
       OR active_embedding_song_count <> 875
       OR catalog_track_count <> 875
       OR embedding_row_count <> 1750
       OR ecapa_row_count <> 875
       OR singer_byol_row_count <> 875
       OR orphan_row_count <> 0
       OR inactive_embedding_row_count <> 0
       OR invalid_model_group_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Active catalog invariants do not match the 875-track repair';
    END IF;

    UPDATE embedding_catalog_batch
    SET track_count = 898
    WHERE batch_id = 'timbre-tj975-ready898-v1';

    UPDATE embedding_catalog_active
    SET revision = 4,
        updated_at = CURRENT_TIMESTAMP(6)
    WHERE channel = 'default'
      AND batch_id = 'timbre-tj975-ready898-v1'
      AND revision = 3;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Active catalog revision changed concurrently';
    END IF;

    INSERT INTO embedding_catalog_event
        (channel, previous_batch_id, batch_id, revision, actor)
    VALUES
        ('default', 'timbre-tj975-ready898-v1', 'timbre-tj975-ready898-v1', 4,
         'incident-repair-active-subset-875');

    COMMIT;

    SELECT active_batch AS batch_id,
           active_revision AS previous_revision,
           4 AS revision,
           source_track_count AS previous_source_track_count,
           898 AS source_track_count,
           active_song_count,
           embedding_song_count,
           embedding_row_count;
END$$

CALL finalize_active_catalog_875()$$
DROP PROCEDURE finalize_active_catalog_875$$

DELIMITER ;
