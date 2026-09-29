-- Preserve every existing row; uniqueness belongs to a shelf rather than the whole recommendation.
ALTER TABLE recommendation_song
    DROP PRIMARY KEY;
ALTER TABLE recommendation_song
    ADD PRIMARY KEY (recommendation_id, recommendation_shelf_id, song_id);
