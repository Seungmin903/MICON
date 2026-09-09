package com.micon.backend.embedding;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.storage.VoiceStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ai.service-token=test-catalog-service-token"})
@AutoConfigureMockMvc
class EmbeddingCatalogApiIntegrationTests {
    private static final String PATH = "/internal/embedding-catalog/active";
    @Autowired MockMvc mvc;
    @MockitoBean EmbeddingCatalogService catalog;
    @MockitoBean VoiceStorage voiceStorage;
    @MockitoBean VoiceAnalysisJobGateway aiGateway;

    @Test
    void authorizedWorkerGetsUncachedSnapshot() throws Exception {
        when(catalog.active()).thenReturn(Map.of("schema_version", 1, "batch_id", "batch-v1", "revision", 7));
        mvc.perform(get(PATH).header("Authorization", "Bearer test-catalog-service-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.batch_id").value("batch-v1"))
                .andExpect(jsonPath("$.data.revision").value(7));
    }

    @Test
    void absentAndInvalidTokensAreRejectedBeforeDatabaseAccess() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer incorrect"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog);
    }

    @Test
    void normalUserJwtDoesNotGrantCatalogAccess() throws Exception {
        mvc.perform(get(PATH).with(jwt())).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog);
    }

    @Test
    void missingActiveCatalogIsUnavailable() throws Exception {
        when(catalog.active()).thenThrow(new BusinessException(ErrorCode.EMBEDDING_CATALOG_UNAVAILABLE));
        mvc.perform(get(PATH).header("Authorization", "Bearer test-catalog-service-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_CATALOG_UNAVAILABLE"));
    }

    @Test
    void serviceCredentialDoesNotAuthorizeWriteMethods() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer test-catalog-service-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog);
    }
}
