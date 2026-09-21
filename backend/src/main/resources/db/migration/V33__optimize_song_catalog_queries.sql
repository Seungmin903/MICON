-- Empty catalog pages are repeatedly read by the Android recommendation screen.
-- These indexes cover its deterministic title ordering and batched artist display order.
CREATE INDEX idx_song_catalog_title ON song (title, song_id);

CREATE INDEX idx_song_singer_display_order ON song_singer (song_id, display_order, singer_id);
