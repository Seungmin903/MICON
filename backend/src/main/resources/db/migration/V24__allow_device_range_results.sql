-- The Android guided-range-v7 flow evaluates pitch on-device and submits the verified
-- result into the same session used by the server timbre analysis.
ALTER TABLE voice_range_measurement
    DROP CONSTRAINT ck_voice_range_measurement_source;

ALTER TABLE voice_range_measurement
    ADD CONSTRAINT ck_voice_range_measurement_source CHECK (source IN ('ON_DEVICE', 'SERVER_AI'));
