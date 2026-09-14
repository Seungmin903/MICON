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
