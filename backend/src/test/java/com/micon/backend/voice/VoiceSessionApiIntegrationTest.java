package com.micon.backend.voice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "ai.voice-analysis-enabled=true")
@AutoConfigureMockMvc
class VoiceSessionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean VoiceAnalysisJobGateway gateway;

    @Test
    void guestStreamsRangeWavToAiAndRecoversPendingSessionWithoutDatabaseAccount() throws Exception {
        when(gateway.submitRange(any())).thenReturn(new VoiceAnalysisJobGateway.Result("range-job-1", "QUEUED"));
        String created = mvc.perform(post("/voice-analysis-sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(created).get("data");
        long sessionId = data.get("sessionId").asLong();
        String token = data.get("guestSessionToken").asText();

        mvc.perform(multipart("/voice-analysis-sessions/{sessionId}/range-analysis", sessionId)
                        .file(rangeWav())
                        .file(meta("guided-range-v7"))
                        .header("X-Guest-Session-Token", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.componentType").value("RANGE"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.aiJobId").value("range-job-1"));

        mvc.perform(get("/voice-analysis-sessions/active").header("X-Guest-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.rangeStatus").value("ANALYZING"));
    }

    @Test
    void rejectsUnsupportedRangeProtocolAndMissingGuestToken() throws Exception {
        mvc.perform(get("/voice-analysis-sessions/active"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_GUEST_SESSION"));

        JsonNode data = json.readTree(mvc.perform(post("/voice-analysis-sessions"))
                .andReturn().getResponse().getContentAsString()).get("data");
        mvc.perform(multipart("/voice-analysis-sessions/{sessionId}/range-analysis", data.get("sessionId").asLong())
                        .file(rangeWav())
                        .file(meta("guided-range-v6"))
                        .header("X-Guest-Session-Token", data.get("guestSessionToken").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private MockMultipartFile meta(String protocol) {
        String body = """
                {"requestId":"0199f9f5-0000-4000-8000-000000000001","attemptNo":1,
                 "measurementId":"device-range-1","measuredAt":"2026-09-14T00:00:00",
                 "protocolVersion":"%s","mode":"basic","startMidi":60,"noiseRms":0.01,
                 "durationSeconds":20,"stopReason":"BOUNDARIES_FOUND",
                 "lowerBoundaryStatus":"FOUND","upperBoundaryStatus":"FOUND",
                 "attempts":[{"targetMidi":60,"direction":"UP"}],"contractVersion":"v1"}
                """.formatted(protocol);
        return new MockMultipartFile("meta", "", MediaType.APPLICATION_JSON_VALUE, body.getBytes());
    }

    private MockMultipartFile rangeWav() {
        return new MockMultipartFile("file", "range.wav", "audio/wav",
                new byte[] {'R','I','F','F', 0,0,0,0, 'W','A','V','E'});
    }
}
