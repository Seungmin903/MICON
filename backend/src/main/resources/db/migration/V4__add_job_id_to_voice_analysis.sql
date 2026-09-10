ALTER TABLE voice_analysis
    ADD COLUMN job_id VARCHAR(255) NULL;

ALTER TABLE voice_analysis
    ADD CONSTRAINT uk_voice_analysis_job_id UNIQUE (job_id);
