-- Review-only grants template. Apply V1..V7 with the Flyway account FIRST.
-- Infrastructure embedding registrar only, NOT the song-metadata uploader.
-- An administrator creates the catalog_importer account with a random password,
-- host restrictions and secure transport separately; no passwords belong in Git.
-- Replace <IMPORTER_HOST> with the actual trusted host, never blindly use %.
-- This user cannot create/drop tables, delete batches or modify song metadata.
GRANT SELECT ON micon.song_metadata TO 'catalog_importer'@'<IMPORTER_HOST>';
GRANT SELECT ON micon.song TO 'catalog_importer'@'<IMPORTER_HOST>';
GRANT SELECT, INSERT ON micon.embedding_catalog_batch TO 'catalog_importer'@'<IMPORTER_HOST>';
GRANT SELECT, INSERT ON micon.song_embedding TO 'catalog_importer'@'<IMPORTER_HOST>';
GRANT SELECT, UPDATE ON micon.embedding_catalog_active TO 'catalog_importer'@'<IMPORTER_HOST>';
GRANT SELECT, INSERT ON micon.embedding_catalog_event TO 'catalog_importer'@'<IMPORTER_HOST>';
