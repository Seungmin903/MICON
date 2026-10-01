ALTER TABLE voice_recording_context
    DROP CHECK ck_voice_recording_context_times,
    ADD CONSTRAINT ck_voice_recording_context_times CHECK (
        selected_start_ms >= 0
        AND actual_start_ms >= 0
        AND accumulated_active_recording_ms >= 28000
    );
