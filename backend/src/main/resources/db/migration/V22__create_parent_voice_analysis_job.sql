CREATE TABLE voice_analysis_job (
 analysis_job_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, session_id BIGINT NOT NULL,
 recording_id BIGINT NULL, request_id VARCHAR(100) NOT NULL UNIQUE, ai_job_id VARCHAR(255) NULL UNIQUE,
 analysis_type VARCHAR(30) NOT NULL, attempt_no INT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 failure_code VARCHAR(100) NULL, failure_reason VARCHAR(1000) NULL, started_at DATETIME(6) NULL,
 completed_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY (session_id) REFERENCES voice_analysis_session(session_id) ON DELETE CASCADE,
 FOREIGN KEY (recording_id) REFERENCES voice_recording(recording_id) ON DELETE SET NULL,
 CHECK (analysis_type='TIMBRE'), CHECK(status IN('PENDING','DISPATCH_UNKNOWN','QUEUED','RUNNING','COMPLETED','FAILED','CANCELED'))
);
ALTER TABLE voice_analysis_component_job ADD COLUMN analysis_job_id BIGINT NULL AFTER component_job_id;
ALTER TABLE voice_analysis_component_job ADD CONSTRAINT fk_voice_component_parent FOREIGN KEY(analysis_job_id) REFERENCES voice_analysis_job(analysis_job_id) ON DELETE CASCADE;
ALTER TABLE voice_analysis_component_job ADD CONSTRAINT uk_voice_component_parent_type UNIQUE(analysis_job_id,component_type);
