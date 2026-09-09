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
