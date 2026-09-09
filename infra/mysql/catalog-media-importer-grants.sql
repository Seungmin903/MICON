-- Review-only grants template. Apply V1..V10 with the Flyway account first.
-- This identity can register immutable vocal-stem references, not modify song metadata.
-- Replace <IMPORTER_HOST> with the trusted host; never use % in production.
GRANT SELECT ON micon.song_metadata TO 'catalog_media_importer'@'<IMPORTER_HOST>';
GRANT SELECT, INSERT ON micon.song_media TO 'catalog_media_importer'@'<IMPORTER_HOST>';
