package com.micon.backend.voice;

import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.storage.VoiceStorage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VoiceDataDeletionApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired VoiceRecordingRepository recordings;
    @Autowired VoiceAnalysisRepository analyses;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean VoiceStorage storage;

    @Test
    void deletesAnalysisAndItsResultRows() throws Exception {
        User user = user("delete-analysis@micon.com");
        VoiceRecording recording = uploadedRecording(user, "voice/delete-analysis.wav");
        VoiceAnalysis analysis = completedAnalysis(user, recording, "delete-analysis-job");
        jdbcTemplate.update("INSERT INTO vocal_range_analysis (voice_analysis_id, measured_at) VALUES (?, CURRENT_TIMESTAMP)",
                analysis.getId());
        jdbcTemplate.update("INSERT INTO vocal_timbre_analysis (voice_analysis_id, measured_at) VALUES (?, CURRENT_TIMESTAMP)",
                analysis.getId());
        entityManager.clear();

        mockMvc.perform(delete("/voice-analyses/{analysisId}", analysis.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(count("voice_analysis", "voice_analysis_id", analysis.getId())).isZero();
        org.assertj.core.api.Assertions.assertThat(count("vocal_range_analysis", "voice_analysis_id", analysis.getId())).isZero();
        org.assertj.core.api.Assertions.assertThat(count("vocal_timbre_analysis", "voice_analysis_id", analysis.getId())).isZero();
    }

    @Test
    void deletesStoredObjectAndSoftDeletesRecordingMetadata() throws Exception {
        User user = user("delete-recording@micon.com");
        VoiceRecording recording = uploadedRecording(user, "voice/delete-recording.wav");
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.fail("UPLOAD_FAILED", "failed before dispatch", LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(delete("/recordings/{recordingId}", recording.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        verify(storage).delete("voice/delete-recording.wav");

        entityManager.flush();
        entityManager.clear();
        VoiceRecording deleted = recordings.findById(recording.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(deleted.getUploadStatus().name()).isEqualTo("DELETED");
        org.assertj.core.api.Assertions.assertThat(deleted.getDeletedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(analyses.existsById(analysis.getId())).isTrue();
    }

    @Test
    void rejectsDeletingDataUsedByAnActiveAnalysis() throws Exception {
        User user = user("delete-active-analysis@micon.com");
        VoiceRecording recording = uploadedRecording(user, "voice/active.wav");
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start("active-job", LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(delete("/voice-analyses/{analysisId}", analysis.getId()).with(jwtFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_IN_PROGRESS"));
        mockMvc.perform(delete("/recordings/{recordingId}", recording.getId()).with(jwtFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_IN_PROGRESS"));
    }

    @Test
    void hidesAnotherUsersVoiceData() throws Exception {
        User owner = user("delete-owner@micon.com");
        User another = user("delete-another@micon.com");
        VoiceRecording recording = uploadedRecording(owner, "voice/private-delete.wav");
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(owner, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.fail("FAILED", "failure", LocalDateTime.now());
        analyses.flush();

        mockMvc.perform(delete("/recordings/{recordingId}", recording.getId()).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
        mockMvc.perform(delete("/voice-analyses/{analysisId}", analysis.getId()).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_NOT_FOUND"));
    }

    private User user(String email) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode("Password123!")));
    }

    private VoiceRecording uploadedRecording(User user, String path) {
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, path, "audio/wav", true));
        recording.completeUpload("a".repeat(64), 1024L);
        recordings.flush();
        return recording;
    }

    private VoiceAnalysis completedAnalysis(User user, VoiceRecording recording, String jobId) {
        VoiceAnalysis analysis = analyses.saveAndFlush(new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE));
        analysis.start(jobId, LocalDateTime.now());
        analysis.complete("v1", null, LocalDateTime.now());
        analyses.flush();
        return analysis;
    }

    private long count(String table, String idColumn, long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?", Long.class, id);
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()).claim("sid", "test-session"));
    }
}
