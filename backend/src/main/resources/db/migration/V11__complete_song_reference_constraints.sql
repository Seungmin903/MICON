-- Complete logical song references that could not be declared before the
-- song catalog tables were introduced by V6. Existing columns are preserved.

ALTER TABLE voice_analysis
    ADD CONSTRAINT fk_voice_analysis_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);

ALTER TABLE voice_analysis
    ADD CONSTRAINT ck_voice_analysis_times
        CHECK (started_at IS NULL OR completed_at IS NULL OR completed_at >= started_at);

ALTER TABLE recommendation_song
    ADD CONSTRAINT fk_recommendation_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);

ALTER TABLE recommendation
    ADD CONSTRAINT ck_recommendation_completed_at
        CHECK (completed_at IS NULL OR completed_at >= generated_at);

ALTER TABLE recommendation
    ADD CONSTRAINT ck_recommendation_expiration
        CHECK (expires_at IS NULL OR expires_at > generated_at);

ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_full
        CHECK (full_low_midi IS NULL OR full_high_midi IS NULL OR full_high_midi >= full_low_midi);

ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_stable
        CHECK (stable_low_midi IS NULL OR stable_high_midi IS NULL OR stable_high_midi >= stable_low_midi);

ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_modal
        CHECK (modal_low_midi IS NULL OR modal_high_midi IS NULL OR modal_high_midi >= modal_low_midi);

ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_falsetto
        CHECK (falsetto_low_midi IS NULL OR falsetto_high_midi IS NULL OR falsetto_high_midi >= falsetto_low_midi);

ALTER TABLE vocal_range_analysis
    ADD CONSTRAINT ck_vocal_range_high_note_hold
        CHECK (high_note_hold_sec IS NULL OR high_note_hold_sec >= 0);

ALTER TABLE vocal_timbre_analysis
    ADD CONSTRAINT ck_vocal_timbre_valid_single_sec
        CHECK (valid_single_sec IS NULL OR valid_single_sec >= 0);

ALTER TABLE vocal_timbre_analysis
    ADD CONSTRAINT ck_vocal_timbre_valid_chunk_count
        CHECK (valid_chunk_count IS NULL OR valid_chunk_count >= 0);

ALTER TABLE vocal_timbre_analysis
    ADD CONSTRAINT ck_vocal_timbre_observed_range
        CHECK (
            observed_core_low_midi IS NULL
            OR observed_core_high_midi IS NULL
            OR observed_core_high_midi >= observed_core_low_midi
        );
