-- Convert the catalog primary key and every referencing column to a stable string identifier.
-- Existing numeric identifiers are preserved as their decimal string representation.

ALTER TABLE recommendation_song DROP FOREIGN KEY fk_recommendation_song_song;
ALTER TABLE song_metadata DROP FOREIGN KEY fk_song_metadata_song;
ALTER TABLE song_analysis DROP FOREIGN KEY fk_song_analysis_song;
ALTER TABLE song_media DROP FOREIGN KEY fk_song_media_song;
ALTER TABLE song_section DROP FOREIGN KEY fk_song_section_song;
ALTER TABLE song_singer DROP FOREIGN KEY fk_song_singer_song;
ALTER TABLE song_genre DROP FOREIGN KEY fk_song_genre_song;
ALTER TABLE song_embedding DROP FOREIGN KEY fk_song_embedding_song;
ALTER TABLE favorite_song DROP FOREIGN KEY fk_favorite_song_song;
ALTER TABLE setlist_item DROP FOREIGN KEY fk_setlist_item_song;
ALTER TABLE practice_session DROP FOREIGN KEY fk_practice_session_song;
ALTER TABLE voice_analysis_session DROP FOREIGN KEY fk_voice_analysis_session_song;
ALTER TABLE voice_recording_context DROP FOREIGN KEY fk_voice_recording_context_song;

ALTER TABLE recommendation_song DROP CONSTRAINT ck_recommendation_song_id;

ALTER TABLE song MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE recommendation_song MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_metadata MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_analysis MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_media MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_section MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_singer MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_genre MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE song_embedding MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE favorite_song MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE setlist_item MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE practice_session MODIFY COLUMN song_id VARCHAR(64) NOT NULL;
ALTER TABLE voice_analysis_session MODIFY COLUMN song_id VARCHAR(64) NULL;
ALTER TABLE voice_recording_context MODIFY COLUMN song_id VARCHAR(64) NOT NULL;

ALTER TABLE recommendation_song
    ADD CONSTRAINT fk_recommendation_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE song_metadata
    ADD CONSTRAINT fk_song_metadata_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_analysis
    ADD CONSTRAINT fk_song_analysis_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_media
    ADD CONSTRAINT fk_song_media_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_section
    ADD CONSTRAINT fk_song_section_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_singer
    ADD CONSTRAINT fk_song_singer_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_genre
    ADD CONSTRAINT fk_song_genre_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE song_embedding
    ADD CONSTRAINT fk_song_embedding_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE favorite_song
    ADD CONSTRAINT fk_favorite_song_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE setlist_item
    ADD CONSTRAINT fk_setlist_item_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE CASCADE;
ALTER TABLE practice_session
    ADD CONSTRAINT fk_practice_session_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
ALTER TABLE voice_analysis_session
    ADD CONSTRAINT fk_voice_analysis_session_song
        FOREIGN KEY (song_id) REFERENCES song (song_id) ON DELETE SET NULL;
ALTER TABLE voice_recording_context
    ADD CONSTRAINT fk_voice_recording_context_song
        FOREIGN KEY (song_id) REFERENCES song (song_id);
