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

INSERT INTO embedding_catalog_active (channel, batch_id, revision) VALUES ('default', NULL, 0);

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
