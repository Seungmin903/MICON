package com.micon.backend.catalogmedia;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogMediaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogMediaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean songExists(String songId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM song WHERE song_id=:songId",
                Map.of("songId", songId), Long.class);
        return count != null && count > 0;
    }

    public Optional<VocalStemRow> findReadyVocalStem(String songId) {
        return jdbc.query("""
                SELECT smedia.media_id, smedia.song_id, metadata.catalog_track_id,
                       smedia.file_path, smedia.mime_type, smedia.file_bytes, smedia.sha256
                FROM song_media smedia
                JOIN song_metadata metadata ON metadata.song_id=smedia.song_id
                WHERE smedia.song_id=:songId
                  AND smedia.kind='VOCAL_STEM'
                  AND smedia.semitones=0
                  AND smedia.generation_status='READY'
                LIMIT 1
                """, Map.of("songId", songId), (row, number) -> new VocalStemRow(
                row.getLong("media_id"),
                row.getString("song_id"),
                row.getString("catalog_track_id"),
                row.getString("file_path"),
                row.getString("mime_type"),
                row.getObject("file_bytes", Long.class),
                row.getString("sha256")
        )).stream().findFirst();
    }

    public record VocalStemRow(long mediaId, String songId, String catalogTrackId, String objectKey,
                               String mimeType, Long fileBytes, String sha256) {
    }
}
