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
