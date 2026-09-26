ALTER TABLE voice_analysis_component_job
    ADD COLUMN voice_quality_json JSON NULL;

ALTER TABLE voice_range_measurement
    ADD COLUMN voice_quality_json JSON NULL;
