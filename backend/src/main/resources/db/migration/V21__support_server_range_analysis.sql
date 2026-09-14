-- Range recordings are forwarded to AI and only their metadata/results are retained by BE.
ALTER TABLE voice_range_measurement
    DROP CONSTRAINT ck_voice_range_measurement_source;

ALTER TABLE voice_range_measurement
    ADD CONSTRAINT ck_voice_range_measurement_source CHECK (source = 'SERVER_AI');

ALTER TABLE voice_analysis_component_job
    DROP CONSTRAINT ck_voice_component_job_type;

ALTER TABLE voice_analysis_component_job
    ADD CONSTRAINT ck_voice_component_job_type CHECK (
        component_type IN ('RANGE', 'ECAPA', 'SINGER_BYOL', 'ARTIST_SEARCH')
    );

ALTER TABLE voice_analysis_component_job
    DROP CONSTRAINT ck_voice_component_job_status;

ALTER TABLE voice_analysis_component_job
    ADD CONSTRAINT ck_voice_component_job_status CHECK (
        status IN ('PENDING', 'DISPATCH_UNKNOWN', 'QUEUED', 'RUNNING', 'COMPLETED',
                   'FAILED', 'INSUFFICIENT_DATA', 'CANCELED')
    );
