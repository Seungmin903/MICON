ALTER TABLE user_setting
    ADD COLUMN preference_onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE
    AFTER save_original_recording;
