package com.micon.backend.voice;

import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.storage.VoiceStorage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@SpringBootTest(properties = "ai.service-token=test-ai-token")
@AutoConfigureMockMvc
@Transactional
class VoiceAnalysisApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired VoiceRecordingRepository recordings;
    @Autowired VoiceAnalysisRepository analyses;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean VoiceAnalysisJobGateway aiGateway;
    @MockitoBean VoiceStorage voiceStorage;

    @Test
    void createsAnalysisForOwnedUploadedRecording() throws Exception {
        User user = user("analysis-owner@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        successfulDispatch("ai-job-1");

        mockMvc.perform(post("/voice-analyses").with(jwtFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordingId":%d,"method":"SCALE"}
                                """.formatted(recording.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.analysisId").isNumber())
                .andExpect(jsonPath("$.data.recordingId").value(recording.getId()))
                .andExpect(jsonPath("$.data.method").value("SCALE"))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());
    }

    @Test
    void rejectsRecordingOwnedByAnotherUser() throws Exception {
        User owner = user("recording-owner@micon.com");
        User requester = user("analysis-requester@micon.com");
        VoiceRecording recording = uploadedRecording(owner);

        mockMvc.perform(post("/voice-analyses").with(jwtFor(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordingId":%d,"method":"SCALE"}
                                """.formatted(recording.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    void rejectsRecordingWhoseUploadIsNotComplete() throws Exception {
        User user = user("uploading-recording@micon.com");
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, "voice/uploading", "audio/wav", false));

        mockMvc.perform(post("/voice-analyses").with(jwtFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordingId":%d,"method":"SCALE"}
                                """.formatted(recording.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_READY"));
    }

    @Test
    void rejectsDuplicateAnalysisWhileOneIsInProgress() throws Exception {
        User user = user("duplicate-analysis@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        String body = """
                {"recordingId":%d,"method":"SCALE"}
                """.formatted(recording.getId());
        successfulDispatch("ai-job-duplicate");

        mockMvc.perform(post("/voice-analyses").with(jwtFor(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/voice-analyses").with(jwtFor(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_IN_PROGRESS"));
    }

    @Test
    void returnsOnlyOwnedAnalysisStatus() throws Exception {
        User owner = user("status-owner@micon.com");
        User another = user("status-another@micon.com");
        VoiceRecording recording = uploadedRecording(owner);
        successfulDispatch("ai-job-status");
        String response = mockMvc.perform(post("/voice-analyses").with(jwtFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordingId":%d,"method":"SCALE"}
                                """.formatted(recording.getId())))
                .andReturn().getResponse().getContentAsString();
        long analysisId = Long.parseLong(response.replaceAll(".*\"analysisId\":(\\d+).*", "$1"));

        mockMvc.perform(get("/voice-analyses/{analysisId}", analysisId).with(jwtFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"));

        mockMvc.perform(get("/voice-analyses/{analysisId}", analysisId).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_NOT_FOUND"));
    }

    @Test
    void recordsFailedStateWhenAiDispatchFails() throws Exception {
        User user = user("analysis-dispatch-failure@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        when(voiceStorage.createDownloadUrl(any(), any()))
                .thenReturn(URI.create("https://storage.test/recording.wav"));
        when(aiGateway.submit(any())).thenThrow(new RuntimeException("AI unavailable"));

        mockMvc.perform(post("/voice-analyses").with(jwtFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordingId":%d,"method":"SCALE"}
                                """.formatted(recording.getId())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_DISPATCH_FAILED"));

        entityManager.flush();
        var failed = jdbcTemplate.queryForMap(
                "SELECT status, failure_code FROM voice_analysis WHERE recording_id = ?", recording.getId());
        org.assertj.core.api.Assertions.assertThat(failed.get("status")).isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(failed.get("failure_code")).isEqualTo("AI_DISPATCH_FAILED");
    }

    @Test
    void cancelsOwnedAnalysisAndIgnoresLateCallback() throws Exception {
        User user = user("analysis-cancel@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start("cancel-job-1", java.time.LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(post("/voice-analyses/{analysisId}/cancel", analysis.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
        verify(aiGateway).cancel("cancel-job-1");

        mockMvc.perform(post("/callbacks/voice-analyses")
                        .header("Authorization", "Bearer test-ai-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voiceAnalysisId":%d,"jobId":"cancel-job-1","status":"FAILED",
                                 "failureCode":"LATE_RESULT","measuredAt":"2026-09-09T02:00:00"}
                                """.formatted(analysis.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    @Test
    void retriesFailedAnalysisWithNewAiJob() throws Exception {
        User user = user("analysis-retry@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start("failed-job", java.time.LocalDateTime.now());
        analysis.fail("AI_ANALYSIS_FAILED", "temporary failure", java.time.LocalDateTime.now());
        analyses.flush();
        successfulDispatch("retry-job");

        mockMvc.perform(post("/voice-analyses/{analysisId}/retry", analysis.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysis.getId()))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.failureCode").doesNotExist())
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        entityManager.flush();
        entityManager.clear();
        org.assertj.core.api.Assertions.assertThat(analyses.findById(analysis.getId()).orElseThrow().getJobId())
                .isEqualTo("retry-job");
    }

    @Test
    void rejectsCancelAndRetryFromInvalidStates() throws Exception {
        User user = user("analysis-invalid-control@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        VoiceAnalysis completed = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        completed.start("completed-job", java.time.LocalDateTime.now());
        completed.complete("v1", null, java.time.LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(post("/voice-analyses/{analysisId}/cancel", completed.getId()).with(jwtFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_VOICE_ANALYSIS_STATE"));
        mockMvc.perform(post("/voice-analyses/{analysisId}/retry", completed.getId()).with(jwtFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_VOICE_ANALYSIS_STATE"));
        verify(aiGateway, never()).cancel("completed-job");
    }

    @Test
    void hidesAnotherUsersAnalysisControl() throws Exception {
        User owner = user("analysis-control-owner@micon.com");
        User another = user("analysis-control-another@micon.com");
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(
                owner, uploadedRecording(owner), null, VoiceAnalysisMethod.SCALE));

        mockMvc.perform(post("/voice-analyses/{analysisId}/cancel", analysis.getId()).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_NOT_FOUND"));
    }

    @Test
    void storesCompletedCallbackResultsAndTreatsRetryAsDuplicate() throws Exception {
        User user = user("analysis-callback@micon.com");
        VoiceRecording recording = uploadedRecording(user);
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start("callback-job-1", java.time.LocalDateTime.now());
        analyses.flush();
        String callback = """
                {
                  "voiceAnalysisId":%d,
                  "jobId":"callback-job-1",
                  "status":"COMPLETED",
                  "analysisVersion":"v1",
                  "qualityStatus":"VALID",
                  "measuredAt":"2026-09-09T01:00:00",
                  "range":{"fullLowMidi":48.0,"fullHighMidi":72.0,"pitchDistribution":{"60":3}},
                  "timbre":{"timbreLabel":"warm","timbreVector":[0.1,0.2]}
                }
                """.formatted(analysis.getId());

        mockMvc.perform(post("/callbacks/voice-analyses")
                        .header("Authorization", "Bearer test-ai-token")
                        .contentType(MediaType.APPLICATION_JSON).content(callback))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.duplicate").value(false));
        entityManager.flush();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vocal_range_analysis WHERE voice_analysis_id = ?", Integer.class, analysis.getId())).isOne();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vocal_timbre_analysis WHERE voice_analysis_id = ?", Integer.class, analysis.getId())).isOne();

        mockMvc.perform(get("/voice-analyses/{analysisId}/report", analysis.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysis.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.range.fullLowMidi").value(48.0))
                .andExpect(jsonPath("$.data.range.pitchDistribution.60").value(3))
                .andExpect(jsonPath("$.data.timbre.timbreLabel").value("warm"))
                .andExpect(jsonPath("$.data.timbre.timbreVector").doesNotExist())
                .andExpect(jsonPath("$.data.timbre.userEmbedding").doesNotExist());

        mockMvc.perform(get("/voice-analyses").with(jwtFor(user)).param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].analysisId").value(analysis.getId()));

        mockMvc.perform(get("/voice-analyses/latest").with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysis.getId()));

        mockMvc.perform(post("/callbacks/voice-analyses")
                        .header("Authorization", "Bearer test-ai-token")
                        .contentType(MediaType.APPLICATION_JSON).content(callback))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    @Test
    void deletesOriginalAfterCompletedAnalysisWhenStorageSettingIsOff() throws Exception {
        User user = user("analysis-delete-original@micon.com");
        VoiceRecording recording = uploadedRecording(user, false, "voice/delete-after-analysis.wav");
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start("delete-original-job", java.time.LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(post("/callbacks/voice-analyses")
                        .header("Authorization", "Bearer test-ai-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "voiceAnalysisId":%d,
                                  "jobId":"delete-original-job",
                                  "status":"COMPLETED",
                                  "analysisVersion":"v1",
                                  "qualityStatus":"VALID",
                                  "measuredAt":"2026-09-09T03:00:00",
                                  "range":{"fullLowMidi":48.0,"fullHighMidi":72.0},
                                  "timbre":{"timbreLabel":"warm"}
                                }
                                """.formatted(analysis.getId())))
                .andExpect(status().isOk());

        verify(voiceStorage).delete("voice/delete-after-analysis.wav");
        entityManager.flush();
        entityManager.clear();
        VoiceRecording deleted = recordings.findById(recording.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(deleted.getUploadStatus().name()).isEqualTo("DELETED");
        org.assertj.core.api.Assertions.assertThat(deleted.getDeletedAt()).isNotNull();
    }

    @Test
    void keepsOriginalAfterTerminalAnalysisWhenStorageSettingIsOn() throws Exception {
        User user = user("analysis-keep-original@micon.com");
        VoiceRecording recording = uploadedRecording(user, true, "voice/keep-after-analysis.wav");
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start("keep-original-job", java.time.LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(post("/callbacks/voice-analyses")
                        .header("Authorization", "Bearer test-ai-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "voiceAnalysisId":%d,
                                  "jobId":"keep-original-job",
                                  "status":"INSUFFICIENT_DATA",
                                  "failureCode":"INSUFFICIENT_DATA",
                                  "measuredAt":"2026-09-09T03:10:00"
                                }
                                """.formatted(analysis.getId())))
                .andExpect(status().isOk());

        verify(voiceStorage, never()).delete("voice/keep-after-analysis.wav");
    }

    @Test
    void hidesAnotherUsersAnalysisReport() throws Exception {
        User owner = user("report-owner@micon.com");
        User another = user("report-another@micon.com");
        VoiceRecording recording = uploadedRecording(owner);
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(owner, recording, null, VoiceAnalysisMethod.SCALE));

        mockMvc.perform(get("/voice-analyses/{analysisId}/report", analysis.getId()).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_NOT_FOUND"));
    }

    @Test
    void rejectsCallbackWithoutAiServiceToken() throws Exception {
        mockMvc.perform(post("/callbacks/voice-analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voiceAnalysisId":1,"jobId":"job","status":"FAILED","measuredAt":"2026-09-09T01:00:00"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_AI_SERVICE_TOKEN"));
    }

    private User user(String email) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode("Password123!")));
    }

    private void successfulDispatch(String jobId) {
        when(voiceStorage.createDownloadUrl(any(), any())).thenReturn(URI.create("https://storage.test/recording.wav"));
        when(aiGateway.submit(any())).thenReturn(new VoiceAnalysisJobGateway.Result(jobId, "QUEUED"));
    }

    private VoiceRecording uploadedRecording(User user) {
        return uploadedRecording(user, false, "voice/" + user.getId());
    }

    private VoiceRecording uploadedRecording(User user, boolean keepOriginal, String path) {
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, path, "audio/wav", keepOriginal));
        jdbcTemplate.update("UPDATE voice_recording SET upload_status = 'UPLOADED' WHERE recording_id = ?",
                recording.getId());
        entityManager.clear();
        return recordings.findById(recording.getId()).orElseThrow();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()).claim("sid", "test-session"));
    }
}
