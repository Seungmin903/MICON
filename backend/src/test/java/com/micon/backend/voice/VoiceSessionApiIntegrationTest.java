package com.micon.backend.voice;

import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisSessionRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.micon.backend.domain.voice.client.VoiceAnalysisJobGateway;
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
import com.micon.backend.domain.user.entity.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@SpringBootTest(properties = "ai.voice-analysis-enabled=true")
@AutoConfigureMockMvc
@Transactional
class VoiceSessionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean VoiceAnalysisJobGateway gateway;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired VoiceAnalysisSessionRepository sessions;

    @Test
    void memberStoresDeviceRangeAndReadsUnifiedResult() throws Exception {
        User user = users.saveAndFlush(new User("voice-session@micon.test", passwords.encode("Password123!")));
        String created = mvc.perform(post("/voice-analysis-sessions").with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long sessionId = json.readTree(created).get("data").get("sessionId").asLong();
        String range = """
                {"measurementId":"device-range-member","measuredAt":"2026-09-14T00:00:00",
                 "protocolVersion":"guided-range-v7","lowMidi":48,"highMidi":72,
                 "noteResults":[{"midi":48,"status":"passed"}],
                 "measurementStatus":"AVAILABLE","qualityStatus":"VALID"}
                """;

        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(range))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rangeStatus").value("AVAILABLE"));

        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(range))
                .andExpect(status().isOk());

        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(range.replace("device-range-member", "different-measurement")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_VOICE_ANALYSIS_STATE"));

        String anotherCreated = mvc.perform(post("/voice-analysis-sessions")
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long anotherSessionId = json.readTree(anotherCreated).get("data").get("sessionId").asLong();
        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", anotherSessionId)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(range))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_VOICE_ANALYSIS_STATE"));

        mvc.perform(get("/voice-analysis-sessions/{sessionId}/result", sessionId)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range.lowMidi").value(48))
                .andExpect(jsonPath("$.data.timbreStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.artistSearchStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.saveAllowed").value(false));

        var session = sessions.findByIdAndUserId(sessionId, user.getId()).orElseThrow();
        session.beginTimbreAnalysis();
        session.acceptTimbreAnalysis();
        session.completeTimbreAnalysis(LocalDateTime.now());
        sessions.flush();

        mvc.perform(post("/voice-analysis-sessions/{sessionId}/profile", sessionId)
                        .with(jwt().jwt(token -> token.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resultVersion\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.profileVersion").value(1));

        mvc.perform(get("/users/me/voice-profile")
                        .with(jwt().jwt(token -> token.subject(user.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.profileVersion").value(1));
    }

    @Test
    void memberStoresHoloDeviceRange() throws Exception {
        User user = users.saveAndFlush(new User("holo-range@micon.test", passwords.encode("Password123!")));
        var member = jwt().jwt(token -> token.subject(user.getId().toString()));
        String created = mvc.perform(post("/voice-analysis-sessions").with(member))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long sessionId = json.readTree(created).get("data").get("sessionId").asLong();
        String range = """
                {"measurementId":"holo-measurement","measuredAt":"2026-09-22T03:14:31",
                 "protocolVersion":"holo-v1","lowMidi":48,"highMidi":72,
                 "noteResults":[{"midi":48,"status":"passed","stability":0.9,"holdMs":300}],
                 "measurementStatus":"AVAILABLE","qualityStatus":"VALID",
                 "voiceQuality":{"version":"personal-acoustic-v1","referenceMidi":57,"points":[
                    {"midi":57,"score":100,"highChange":0,"lowIrregularity":0,"holdScore":100},
                    {"midi":59,"score":62.5,"highChange":0.5,"lowIrregularity":0,"holdScore":100}]}}
                """;

        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(member)
                        .contentType(MediaType.APPLICATION_JSON).content(range))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rangeStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.range.voiceQuality.points[1].score").value(62.5));

        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(member).contentType(MediaType.APPLICATION_JSON).content(range))
                .andExpect(status().isOk());
        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(member).contentType(MediaType.APPLICATION_JSON)
                        .content(range.replace("\"score\":62.5", "\"score\":62")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .with(member).contentType(MediaType.APPLICATION_JSON)
                        .content(range.replace("\"score\":62.5", "\"score\":70")
                                .replace("\"highChange\":0.5", "\"highChange\":0.4")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(get("/voice-analysis-sessions/{sessionId}/result", sessionId).with(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range.voiceQuality.version").value("personal-acoustic-v1"));
    }

    @Test
    void memberStreamsRangeWavToAiAndPersistsAnalyzingState() throws Exception {
        when(gateway.submitRange(any())).thenReturn(new VoiceAnalysisJobGateway.Result("range-job-member", "QUEUED"));
        User user = users.saveAndFlush(new User("voice-range-member@micon.test", passwords.encode("Password123!")));
        var member = jwt().jwt(token -> token.subject(user.getId().toString()));
        String created = mvc.perform(post("/voice-analysis-sessions").with(member))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long sessionId = json.readTree(created).get("data").get("sessionId").asLong();

        mvc.perform(multipart("/voice-analysis-sessions/{sessionId}/range-analysis", sessionId)
                        .file(rangeWav())
                        .file(meta("guided-range-v7"))
                        .with(member))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.componentType").value("RANGE"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.aiJobId").value("range-job-member"));

        mvc.perform(get("/voice-analysis-sessions/{sessionId}/result", sessionId).with(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.rangeStatus").value("ANALYZING"));
    }

    @Test
    void latestProfileRequiresAuthentication() throws Exception {
        mvc.perform(get("/users/me/voice-profile"))
                .andExpect(status().isUnauthorized());
    }

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

    @Test
    void guestRangeSubmissionIsIdempotentOnlyForTheSameMeasurement() throws Exception {
        JsonNode data = json.readTree(mvc.perform(post("/voice-analysis-sessions"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
        long sessionId = data.get("sessionId").asLong();
        String token = data.get("guestSessionToken").asText();
        String range = """
                {"measurementId":"guest-measurement","measuredAt":"2026-09-14T00:00:00",
                 "protocolVersion":"guided-range-v7","lowMidi":48,"highMidi":72,
                 "noteResults":[{"midi":48,"status":"passed"}],
                 "measurementStatus":"AVAILABLE","qualityStatus":"VALID"}
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                            .header("X-Guest-Session-Token", token)
                            .contentType(MediaType.APPLICATION_JSON).content(range))
                    .andExpect(status().isOk());
        }
        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .header("X-Guest-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(range.replace("guest-measurement", "different-measurement")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_VOICE_ANALYSIS_STATE"));
    }

    @Test
    void guestHoloQualitySurvivesSessionRead() throws Exception {
        JsonNode data = json.readTree(mvc.perform(post("/voice-analysis-sessions"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
        long sessionId = data.get("sessionId").asLong();
        String token = data.get("guestSessionToken").asText();
        String range = """
                {"measurementId":"guest-holo-quality","measuredAt":"2026-09-26T00:00:00",
                 "protocolVersion":"holo-v1","lowMidi":48,"highMidi":72,"noteResults":[],
                 "measurementStatus":"AVAILABLE","qualityStatus":"VALID",
                 "voiceQuality":{"version":"personal-acoustic-v1","referenceMidi":57,"points":[
                    {"midi":57,"score":100,"highChange":0,"lowIrregularity":0,"holdScore":100}]}}
                """;
        mvc.perform(put("/voice-analysis-sessions/{sessionId}/range-result", sessionId)
                        .header("X-Guest-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(range))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range.voiceQuality.referenceMidi").value(57));
        mvc.perform(get("/voice-analysis-sessions/active").header("X-Guest-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range.voiceQuality.points[0].score").value(100));
    }

    private MockMultipartFile meta(String protocol) {
        String body = """
                {"requestId":"0199f9f5-0000-4000-8000-000000000001","attemptNo":1,
                 "measurementId":"device-range-1","measuredAt":"2026-09-14T00:00:00",
                 "protocolVersion":"%s","mode":"basic","startMidi":60,"noiseRms":0.01,
                 "durationSeconds":20,"stopReason":"BOUNDARIES_FOUND",
                 "lowerBoundaryStatus":"FOUND","upperBoundaryStatus":"FOUND",
                 "attempts":[{"targetMidi":60,"direction":"UP"}],"contractVersion":"v1",
                 "sampling":{"strategy":"adaptive","sampleRate":16000},"registerEvents":[]}
                """.formatted(protocol);
        return new MockMultipartFile("meta", "", MediaType.APPLICATION_JSON_VALUE, body.getBytes());
    }

    private MockMultipartFile rangeWav() {
        return new MockMultipartFile("file", "range.wav", "audio/wav",
                new byte[] {'R','I','F','F', 0,0,0,0, 'W','A','V','E'});
    }
}
