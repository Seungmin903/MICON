-- Replace relationships that depended on the legacy, single-result voice analysis model.
--
-- recommendation.profile_id is intentionally nullable during this migration. Existing
-- recommendation rows cannot be mapped automatically because the legacy analysis rows
-- do not identify a saved v4 profile. New application writes must always provide it.

ALTER TABLE recommendation
    ADD COLUMN profile_id BIGINT NULL;

ALTER TABLE recommendation
    ADD CONSTRAINT fk_recommendation_voice_profile
        FOREIGN KEY (profile_id) REFERENCES user_voice_profile (profile_id);

CREATE INDEX idx_recommendation_profile
    ON recommendation (profile_id);

ALTER TABLE recommendation
    DROP FOREIGN KEY fk_recommendation_voice_analysis;

DROP INDEX idx_recommendation_voice_analysis
    ON recommendation;

ALTER TABLE recommendation
    DROP COLUMN voice_analysis_id;


-- Practice evaluation owns its result through practice_result. It no longer points
-- at an onboarding voice analysis.
ALTER TABLE practice_session
    DROP FOREIGN KEY fk_practice_session_voice_analysis;

DROP INDEX idx_practice_session_voice_analysis
    ON practice_session;

ALTER TABLE practice_session
    DROP COLUMN voice_analysis_id;


-- Remove the legacy result tables from child to parent after every external FK has
-- been detached. The v4 replacements are created by V17.
DROP TABLE vocal_timbre_analysis;
DROP TABLE vocal_range_analysis;
DROP TABLE voice_analysis;
