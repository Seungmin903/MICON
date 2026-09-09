package com.micon.backend.recommendation;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.application.RecommendationDispatchResult;
import com.micon.backend.recommendation.application.RecommendationDispatchService;
import com.micon.backend.recommendation.client.RecommendationJobGateway;
import com.micon.backend.recommendation.domain.Recommendation;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.recommendation.domain.RecommendationRepository;
import com.micon.backend.recommendation.domain.RecommendationStatus;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.domain.VocalTimbreAnalysis;
import com.micon.backend.voice.domain.VocalTimbreAnalysisRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class RecommendationDispatchIntegrationTest {
    @Autowired RecommendationDispatchService service;
    @Autowired RecommendationRepository recommendations;
    @Autowired UserRepository users;
    @Autowired VoiceRecordingRepository recordings;
    @Autowired VoiceAnalysisRepository analyses;
    @Autowired VocalTimbreAnalysisRepository timbres;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean RecommendationJobGateway gateway;

    @Test
    void createsGeneratingRecommendationAndStoresAiJobId() {
        VoiceAnalysis analysis = completedAnalysis("recommend-dispatch@micon.com");
        when(gateway.submit(any())).thenReturn(new RecommendationJobGateway.Result("job-success", "queued"));

        RecommendationDispatchResult result = service.dispatch(
                analysis.getUser().getId(), analysis.getId(), RecommendationMode.OVERALL);

        entityManager.flush();
        entityManager.clear();
        Recommendation saved = recommendations.findById(result.recommendationId()).orElseThrow();
        assertThat(saved.getJobId()).isEqualTo("job-success");
        assertThat(saved.getStatus()).isEqualTo(RecommendationStatus.GENERATING);
        assertThat(saved.getProgress()).isZero();
    }

    @Test
    void keepsFailedRecommendationWhenAiDispatchFails() {
        VoiceAnalysis analysis = completedAnalysis("recommend-failure@micon.com");
        when(gateway.submit(any())).thenThrow(new RuntimeException("AI connection timed out"));

        assertThatThrownBy(() -> service.dispatch(
                analysis.getUser().getId(), analysis.getId(), RecommendationMode.TIMBRE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RECOMMENDATION_DISPATCH_FAILED));

        entityManager.flush();
        entityManager.clear();
        Recommendation failed = recommendations.findFirstByUserIdOrderByGeneratedAtDescIdDesc(
                analysis.getUser().getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RecommendationStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("AI_DISPATCH_FAILED");
        assertThat(failed.getFailureReason()).isEqualTo("AI connection timed out");
    }

    private VoiceAnalysis completedAnalysis(String email) {
        User user = users.saveAndFlush(new User(email, passwordEncoder.encode("Password123!")));
        VoiceRecording recording = recordings.saveAndFlush(
                new VoiceRecording(user, (short) 1, "voice/recording.wav", "audio/wav", false));
        jdbc.update("UPDATE voice_recording SET upload_status = 'UPLOADED' WHERE recording_id = ?",
                recording.getId());
        entityManager.clear();
        recording = recordings.findById(recording.getId()).orElseThrow();
        VoiceAnalysis analysis = new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE);
        analysis.start("voice-job", LocalDateTime.now());
        analysis.complete("analysis-v1", null, LocalDateTime.now());
        analysis = analyses.saveAndFlush(analysis);
        timbres.saveAndFlush(new VocalTimbreAnalysis(
                analysis, "맑은 음색", null, null, null, null, null, null, null,
                "{\"ecapa\":[0.1],\"singerByol\":[0.2]}", null, null,
                null, null, null, null, LocalDateTime.now()));
        return analysis;
    }
}
