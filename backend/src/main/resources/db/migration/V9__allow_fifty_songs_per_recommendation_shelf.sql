ALTER TABLE recommendation_song DROP CONSTRAINT ck_recommendation_song_rank;
ALTER TABLE recommendation_song
    ADD CONSTRAINT ck_recommendation_song_rank CHECK (overall_rank > 0);
ALTER TABLE recommendation_song DROP CONSTRAINT ck_recommendation_song_shelf_position;
ALTER TABLE recommendation_song
    ADD CONSTRAINT ck_recommendation_song_shelf_position CHECK (shelf_position BETWEEN 1 AND 50);

ALTER TABLE recommendation_shelf MODIFY COLUMN metric VARCHAR(255) NULL;
