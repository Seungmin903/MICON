-- Persist deletion work so an object-store outage or application restart cannot lose it.
CREATE TABLE voice_recording_deletion_job (
    deletion_job_id BIGINT NOT NULL AUTO_INCREMENT,
    recording_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_voice_recording_deletion_job PRIMARY KEY (deletion_job_id),
    CONSTRAINT uk_voice_recording_deletion_recording UNIQUE (recording_id),
    CONSTRAINT fk_voice_recording_deletion_recording
        FOREIGN KEY (recording_id) REFERENCES voice_recording (recording_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_voice_recording_deletion_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'FAILED', 'COMPLETED')
    ),
    CONSTRAINT ck_voice_recording_deletion_attempt CHECK (attempt_count >= 0),
    INDEX idx_voice_recording_deletion_retry (status, next_attempt_at)
);
