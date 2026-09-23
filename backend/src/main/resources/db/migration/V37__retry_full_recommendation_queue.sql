ALTER TABLE recommendation
    ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0;

ALTER TABLE recommendation
    ADD COLUMN dispatch_retry_at DATETIME(6) NULL;

CREATE INDEX idx_recommendation_dispatch_retry
    ON recommendation (status, job_id, dispatch_retry_at, generated_at);
