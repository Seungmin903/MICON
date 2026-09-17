package com.micon.backend.catalogmedia;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.storage.VoiceStorage;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "ai.service-token=test-catalog-media-token")
@AutoConfigureMockMvc
class CatalogMediaApiIntegrationTests {

    private static final String PATH = "/internal/catalog-media/vocal-stems/212";

    @Autowired MockMvc mvc;
    @MockitoBean CatalogMediaService media;
    @MockitoBean VoiceStorage voiceStorage;
    @MockitoBean VoiceAnalysisJobGateway aiGateway;

    @Test
    void authorizedAiWorkerGetsUncachedDownloadReference() throws Exception {
        when(media.vocalStem("212")).thenReturn(new CatalogMediaResponse(
                10L, "212", "0156ac758f1192ee", "VOCAL_STEM",
                "catalog/정영주/section-384-v1/vocals/0156ac758f1192ee/vocals.wav",
                "audio/wav", 37_875_760L, "a".repeat(64),
                "https://signed.example/vocal", Instant.parse("2026-09-10T00:10:00Z")));

        mvc.perform(get(PATH).header("Authorization", "Bearer test-catalog-media-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.songId").value(212))
                .andExpect(jsonPath("$.data.catalogTrackId").value("0156ac758f1192ee"))
                .andExpect(jsonPath("$.data.mediaType").value("VOCAL_STEM"))
                .andExpect(jsonPath("$.data.contentUrl").value("https://signed.example/vocal"));
    }

    @Test
    void absentInvalidAndUserTokensAreRejectedBeforeMediaAccess() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer incorrect"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(jwt())).andExpect(status().isUnauthorized());
        verifyNoInteractions(media);
    }

    @Test
    void serviceTokenDoesNotAuthorizeWriteMethods() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer test-catalog-media-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(media);
    }
}
