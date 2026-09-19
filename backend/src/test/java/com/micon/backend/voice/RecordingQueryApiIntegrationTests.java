package com.micon.backend.voice;

import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingRepository;

import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.voice.entity.VoiceRecording;
import com.micon.backend.domain.voice.storage.VoiceStorage;
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

import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecordingQueryApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired VoiceRecordingRepository recordings;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean VoiceStorage storage;

    @Test
    void listsOwnedNonDeletedRecordingsWithoutStorageMetadata() throws Exception {
        User user = user("recording-list@micon.com");
        VoiceRecording first = uploadedRecording(user, "voice/first.wav", true);
        VoiceRecording second = uploadedRecording(user, "voice/second.wav", false);
        VoiceRecording deleted = uploadedRecording(user, "voice/deleted.wav", true);
        jdbcTemplate.update("UPDATE voice_recording SET deleted_at = CURRENT_TIMESTAMP, upload_status = 'DELETED' WHERE recording_id = ?",
                deleted.getId());
        entityManager.clear();

        mockMvc.perform(get("/recordings").with(jwtFor(user)).param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].recordingId").value(second.getId()))
                .andExpect(jsonPath("$.data.content[1].recordingId").value(first.getId()))
                .andExpect(jsonPath("$.data.content[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].sha256").doesNotExist());
    }

    @Test
    void returnsOwnedRecordingDetailAndPlaybackUrl() throws Exception {
        User user = user("recording-playback@micon.com");
        VoiceRecording recording = uploadedRecording(user, "voice/playback.wav", true);
        when(storage.createDownloadUrl(eq("voice/playback.wav"), any()))
                .thenReturn(URI.create("https://storage.test/playback.wav?signature=test"));

        mockMvc.perform(get("/recordings/{recordingId}", recording.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordingId").value(recording.getId()))
                .andExpect(jsonPath("$.data.mediaType").value("audio/wav"))
                .andExpect(jsonPath("$.data.keepOriginal").value(true));

        mockMvc.perform(get("/recordings/{recordingId}/content", recording.getId()).with(jwtFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordingId").value(recording.getId()))
                .andExpect(jsonPath("$.data.contentUrl").value("https://storage.test/playback.wav?signature=test"))
                .andExpect(jsonPath("$.data.mediaType").value("audio/wav"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
        verify(storage).createDownloadUrl(eq("voice/playback.wav"), any());
    }

    @Test
    void hidesAnotherUsersAndDeletedRecordings() throws Exception {
        User owner = user("recording-owner@micon.com");
        User another = user("recording-another@micon.com");
        VoiceRecording recording = uploadedRecording(owner, "voice/private.wav", true);

        mockMvc.perform(get("/recordings/{recordingId}", recording.getId()).with(jwtFor(another)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));

        jdbcTemplate.update("UPDATE voice_recording SET deleted_at = CURRENT_TIMESTAMP, upload_status = 'DELETED' WHERE recording_id = ?",
                recording.getId());
        entityManager.clear();
        mockMvc.perform(get("/recordings/{recordingId}/content", recording.getId()).with(jwtFor(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
    }

    @Test
    void rejectsPlaybackBeforeUploadCompletesAndInvalidPagination() throws Exception {
        User user = user("recording-invalid-query@micon.com");
        VoiceRecording uploading = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, "voice/uploading.wav", "audio/wav", false));

        mockMvc.perform(get("/recordings/{recordingId}/content", uploading.getId()).with(jwtFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_READY"));
        mockMvc.perform(get("/recordings").with(jwtFor(user)).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private User user(String email) {
        return users.saveAndFlush(new User(email, passwordEncoder.encode("Password123!")));
    }

    private VoiceRecording uploadedRecording(User user, String path, boolean keepOriginal) {
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, path, "audio/wav", keepOriginal));
        recording.completeUpload("a".repeat(64), 1024L);
        recordings.flush();
        return recording;
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()).claim("sid", "test-session"));
    }
}
