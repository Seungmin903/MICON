-- Server-side range analysis uses transient upload and analysis states before
-- the final AVAILABLE / INSUFFICIENT_DATA / FAILED result is persisted.
ALTER TABLE voice_analysis_session
    DROP CONSTRAINT ck_voice_analysis_session_range_status;

ALTER TABLE voice_analysis_session
    ADD CONSTRAINT ck_voice_analysis_session_range_status CHECK (
        range_status IN (
            'PENDING', 'IN_PROGRESS', 'UPLOADING', 'ANALYZING',
            'AVAILABLE', 'INSUFFICIENT_DATA', 'FAILED'
        )
    );
