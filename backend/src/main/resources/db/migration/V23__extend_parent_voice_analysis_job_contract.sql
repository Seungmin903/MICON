-- Extend the parent job created by V22 without recreating tables owned by the range/session work.
ALTER TABLE voice_analysis_job
    ADD COLUMN audio_sha256 CHAR(64) NULL AFTER request_id;

ALTER TABLE voice_analysis_job
    ADD COLUMN audio_expires_at DATETIME(6) NULL AFTER status;

ALTER TABLE voice_analysis_job
    ADD COLUMN duplicate BOOLEAN NOT NULL DEFAULT FALSE AFTER audio_expires_at;

CREATE INDEX idx_voice_analysis_job_recording
    ON voice_analysis_job (recording_id);

CREATE INDEX idx_voice_analysis_job_status_created
    ON voice_analysis_job (status, created_at);
