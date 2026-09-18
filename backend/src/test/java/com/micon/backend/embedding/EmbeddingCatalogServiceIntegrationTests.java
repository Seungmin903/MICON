package com.micon.backend.embedding;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.storage.VoiceStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class EmbeddingCatalogServiceIntegrationTests {
    @Autowired EmbeddingCatalogService catalog;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean VoiceStorage voiceStorage;
    @MockitoBean VoiceAnalysisJobGateway aiGateway;

    private void seed() {
        jdbc.update("INSERT INTO song(song_id,title) VALUES (9001,'catalog test')");
        jdbc.update("""
                INSERT INTO embedding_catalog_batch
                (batch_id,bucket,region,object_prefix,manifest_key,manifest_sha256,
                 manifest_bytes,models_json,track_count)
                VALUES ('test-batch','test-bucket','ap-northeast-2','catalog/test/test-batch/',
                        'catalog/test/test-batch/manifest.json',?,1000,? FORMAT JSON,1)
                """, "a".repeat(64), "{\"ecapa\":{\"dimension\":192},\"singer_byol\":{\"dimension\":1000}}");
        for (String model : List.of("ecapa", "singer_byol")) {
            jdbc.update("""
                    INSERT INTO song_embedding
                    (batch_id,song_id,catalog_track_id,model_key,object_key,sha256,
                     file_bytes,segment_count,dimension)
                    VALUES ('test-batch',9001,'POOL_9001',?,?,?,2048,2,?)
                    """, model, "catalog/test/test-batch/" + model + ".npy",
                    "b".repeat(64), model.equals("ecapa") ? 192 : 1000);
        }
        jdbc.update("UPDATE embedding_catalog_active SET batch_id='test-batch',revision=4 WHERE channel='default'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualJdbcSnapshotPreservesSongMappingAndModelReferences() {
        seed();
        Map<String, Object> snapshot = catalog.active();
        assertThat(snapshot).containsEntry("batch_id", "test-batch").containsEntry("revision", 4L);
        assertThat((Map<String, Object>) snapshot.get("models")).containsKeys("ecapa", "singer_byol");
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) snapshot.get("tracks");
        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst()).containsEntry("song_id", "9001").containsEntry("catalog_track_id", "POOL_9001");
        Map<String, Map<String, Object>> embeddings =
                (Map<String, Map<String, Object>>) tracks.getFirst().get("embeddings");
        assertThat(embeddings.get("ecapa")).containsEntry("dimension", 192).containsEntry("rows", 2)
                .containsEntry("key", "catalog/test/test-batch/ecapa.npy");
    }

    @Test
    void incompleteBatchIsNotServed() {
        seed();
        jdbc.update("DELETE FROM song_embedding WHERE batch_id='test-batch' AND model_key='ecapa'");
        unavailable();
    }

    @Test
    void inactiveCatalogIsNotServed() {
        jdbc.update("UPDATE embedding_catalog_active SET batch_id=NULL WHERE channel='default'");
        unavailable();
    }

    private void unavailable() {
        assertThatThrownBy(catalog::active).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EMBEDDING_CATALOG_UNAVAILABLE));
    }
}
