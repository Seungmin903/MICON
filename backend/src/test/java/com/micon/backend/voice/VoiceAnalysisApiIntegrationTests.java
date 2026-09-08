package com.micon.backend.voice;

import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VoiceAnalysisApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired VoiceRecordingRepository recordings;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @Test
    void createsAnalysisForOwnedUploadedRecording() throws Exception {
        User user = user("analysis-owner@micon.com");
        VoiceRecording recording = uploadedRecording(user);

        mockMvc.perform(post("/voice-analyses").with(jwtFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordingId":%d,"method":"SCALE"}
                                """.formatted(recording.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.analysisId").isNumber())
                .andExpect(jsonPath("$.data.recordingId").value(recording.getId()))
                .andExpect(jsonPath("$.data.method").value("SCALE"))
                .andExpect(jsonPath("$.data.status").value("READY"));
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
                .andExpect(jsonPath("$.data.status").value("READY"));

        mockMvc.perform(get("/voice-analyses/{analysisId}", analysisId).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOICE_ANALYSIS_NOT_FOUND"));
    }

    private User user(String email) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode("Password123!")));
    }

    private VoiceRecording uploadedRecording(User user) {
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, "voice/" + user.getId(), "audio/wav", false));
        jdbcTemplate.update("UPDATE voice_recording SET upload_status = 'UPLOADED' WHERE recording_id = ?",
                recording.getId());
        entityManager.clear();
        return recordings.findById(recording.getId()).orElseThrow();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()).claim("sid", "test-session"));
    }
}
