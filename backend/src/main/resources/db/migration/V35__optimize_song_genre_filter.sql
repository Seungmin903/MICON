-- Genre-filtered catalog pages start from this access path.
CREATE INDEX idx_song_genre_genre_song ON song_genre (genre_id, song_id);
