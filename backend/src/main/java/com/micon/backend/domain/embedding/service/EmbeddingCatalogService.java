package com.micon.backend.domain.embedding.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One consistent, authenticated metadata snapshot; no S3 IO on the request path.
 * The immutable S3 batch may be a superset. Only ACTIVE DB songs are serviceable. */
@Service
public class EmbeddingCatalogService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public EmbeddingCatalogService(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> active() {
        List<Map<String, Object>> batches = jdbc.query("""
                SELECT a.revision, b.batch_id, b.bucket, b.region, b.manifest_key,
                       b.manifest_sha256, b.manifest_bytes,
                       b.track_count AS source_track_count, b.models_json
                FROM embedding_catalog_active a
                JOIN embedding_catalog_batch b ON b.batch_id = a.batch_id
                WHERE a.channel = 'default'
                """, Map.of(), (row, number) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema_version", 1);
            result.put("channel", "default");
            result.put("revision", row.getLong("revision"));
            for (String column : List.of("batch_id", "bucket", "region", "manifest_key", "manifest_sha256")) {
                result.put(column, row.getString(column));
            }
            result.put("manifest_bytes", row.getInt("manifest_bytes"));
            result.put("source_track_count", row.getInt("source_track_count"));
            try {
                result.put("models", json.readValue(row.getString("models_json"), Map.class));
            } catch (JacksonException invalid) {
                throw new BusinessException(ErrorCode.EMBEDDING_CATALOG_UNAVAILABLE);
            }
            return result;
        });
        if (batches.isEmpty()) {
            throw new BusinessException(ErrorCode.EMBEDDING_CATALOG_UNAVAILABLE);
        }
        Map<String, Object> result = batches.getFirst();
        Map<String, Map<String, Object>> tracks = new LinkedHashMap<>();
        jdbc.query("""
                SELECT se.song_id, se.catalog_track_id, se.model_key, se.object_key, se.sha256,
                       se.file_bytes, se.segment_count, se.dimension
                FROM song_embedding se
                JOIN song s ON s.song_id = se.song_id
                WHERE se.batch_id = :batchId
                  AND s.status = 'ACTIVE'
                ORDER BY se.catalog_track_id, se.model_key
                """, Map.of("batchId", result.get("batch_id")), row -> {
            String id = row.getString("catalog_track_id");
            String songId = row.getString("song_id");
            Map<String, Object> track = tracks.computeIfAbsent(id, key -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("song_id", songId);
                entry.put("catalog_track_id", key);
                entry.put("embeddings", new LinkedHashMap<String, Object>());
                return entry;
            });
            if (!track.get("song_id").equals(songId)) {
                throw new BusinessException(ErrorCode.EMBEDDING_CATALOG_UNAVAILABLE);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> embeddings = (Map<String, Object>) track.get("embeddings");
            embeddings.put(row.getString("model_key"), Map.of(
                    "key", row.getString("object_key"), "sha256", row.getString("sha256"),
                    "bytes", row.getInt("file_bytes"), "rows", row.getInt("segment_count"),
                    "dimension", row.getInt("dimension")));
        });
        if (tracks.isEmpty() || tracks.values().stream().anyMatch(track ->
                    !((Map<?, ?>) track.get("embeddings")).keySet().equals(Set.of("ecapa", "singer_byol")))) {
            throw new BusinessException(ErrorCode.EMBEDDING_CATALOG_UNAVAILABLE);
        }
        result.put("track_count", tracks.size());
        result.put("tracks", List.copyOf(tracks.values()));
        return result;
    }
}
