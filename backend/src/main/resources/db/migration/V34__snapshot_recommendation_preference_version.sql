ALTER TABLE user_setting
    ADD COLUMN preference_version BIGINT NOT NULL DEFAULT 0
    AFTER preference_onboarding_completed;

-- NULL deliberately denotes recommendations created before preference snapshots.
ALTER TABLE recommendation
    ADD COLUMN preference_version BIGINT NULL
    AFTER profile_version;

CREATE INDEX idx_recommendation_preference_snapshot
    ON recommendation (user_id, preference_version, status);
