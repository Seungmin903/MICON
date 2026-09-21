ALTER TABLE voice_range_measurement
    ADD COLUMN stable_low_midi DECIMAL(6,2) NULL;

ALTER TABLE voice_range_measurement
    ADD COLUMN stable_high_midi DECIMAL(6,2) NULL;
