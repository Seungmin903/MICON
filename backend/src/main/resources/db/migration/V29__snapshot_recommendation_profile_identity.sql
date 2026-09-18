-- Do not backfill from mutable profiles: legacy recommendations may belong to
-- an earlier session. NULL identity deliberately excludes them from latest/fit.
ALTER TABLE recommendation
    ADD COLUMN source_session_id BIGINT NULL;
ALTER TABLE recommendation ADD COLUMN profile_version BIGINT NULL;

CREATE INDEX idx_recommendation_profile_snapshot
    ON recommendation (profile_id, profile_version, source_session_id, status);
