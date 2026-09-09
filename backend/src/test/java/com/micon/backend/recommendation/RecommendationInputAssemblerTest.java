package com.micon.backend.recommendation;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.application.RecommendationInput;
import com.micon.backend.recommendation.application.RecommendationInputAssembler;
import com.micon.backend.recommendation.application.RecommendationPreferenceReader;
import com.micon.backend.user.domain.User;
import com.micon.backend.voice.domain.VocalRangeAnalysis;
import com.micon.backend.voice.domain.VocalRangeAnalysisRepository;
import com.micon.backend.voice.domain.VocalTimbreAnalysis;
import com.micon.backend.voice.domain.VocalTimbreAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceRecording;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationInputAssemblerTest {

    @Mock VoiceAnalysisRepository analyses;
    @Mock VocalRangeAnalysisRepository rangeResults;
    @Mock VocalTimbreAnalysisRepository timbreResults;
    @Mock RecommendationPreferenceReader preferences;

    RecommendationInputAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new RecommendationInputAssembler(analyses, rangeResults, timbreResults, preferences);
    }

    @Test
    void assemblesCompletedOwnedAnalysisProfileEmbeddingAndPreferences() {
        VoiceAnalysis analysis = completedAnalysis("recommendation-input@micon.com");
        VocalRangeAnalysis range = range(analysis);
        VocalTimbreAnalysis timbre = timbre(analysis);
        when(analyses.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(analysis));
        when(rangeResults.findByVoiceAnalysisId(10L)).thenReturn(Optional.of(range));
        when(timbreResults.findByVoiceAnalysisId(10L)).thenReturn(Optional.of(timbre));
        when(preferences.findByUserId(1L)).thenReturn(
                new RecommendationPreferenceReader.Preferences(List.of("발라드", "록"), List.of("아이유")));

        RecommendationInput input = assembler.assemble(1L, 10L);

        assertThat(input.voiceAnalysisId()).isEqualTo(10L);
        assertThat(input.sourceSongId()).isNull();
        assertThat(input.genres()).containsExactly("발라드", "록");
        assertThat(input.preferredArtists()).containsExactly("아이유");
        assertThat(input.voiceProfile().stableLowMidi()).isEqualByComparingTo("48.5");
        assertThat(input.voiceProfile().stableHighMidi()).isEqualByComparingTo("70.5");
        assertThat(input.voiceProfile().userEmbedding()).isEqualTo("{\"ecapa\":[0.1]}");
        assertThat(input.voiceProfile().embeddingRef()).isEqualTo("embeddings/user-1.json");
    }

    @Test
    void rejectsAnalysisWhenUserEmbeddingWasNotStored() {
        VoiceAnalysis analysis = completedAnalysis("recommendation-empty@micon.com");
        when(analyses.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(analysis));
        when(rangeResults.findByVoiceAnalysisId(10L)).thenReturn(Optional.empty());
        when(timbreResults.findByVoiceAnalysisId(10L)).thenReturn(Optional.empty());
        assertError(() -> assembler.assemble(1L, 10L), ErrorCode.RECOMMENDATION_INPUT_NOT_READY);
    }

    @Test
    void hidesAnotherUsersAnalysisAsNotFound() {
        when(analyses.findByIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertError(() -> assembler.assemble(2L, 10L), ErrorCode.VOICE_ANALYSIS_NOT_FOUND);
    }

    @Test
    void rejectsAnalysisThatIsNotCompleted() {
        VoiceAnalysis analysis = analysis("recommendation-pending@micon.com");
        when(analyses.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(analysis));

        assertError(() -> assembler.assemble(1L, 10L), ErrorCode.RECOMMENDATION_INPUT_NOT_READY);
    }

    @Test
    void usesStoredEmbeddingEvenWhenOriginalRecordingWasDeleted() {
        VoiceAnalysis analysis = completedAnalysis("recommendation-deleted@micon.com");
        VocalTimbreAnalysis timbre = timbre(analysis);
        analysis.getRecording().markDeletePending(LocalDateTime.now());
        analysis.getRecording().markDeleted(LocalDateTime.now());
        when(analyses.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(analysis));
        when(rangeResults.findByVoiceAnalysisId(10L)).thenReturn(Optional.empty());
        when(timbreResults.findByVoiceAnalysisId(10L)).thenReturn(Optional.of(timbre));
        when(preferences.findByUserId(1L)).thenReturn(RecommendationPreferenceReader.Preferences.empty());

        RecommendationInput input = assembler.assemble(1L, 10L);

        assertThat(input.voiceProfile().userEmbedding()).isEqualTo("{\"ecapa\":[0.1]}");
    }

    private VoiceAnalysis completedAnalysis(String email) {
        VoiceAnalysis analysis = analysis(email);
        analysis.start("voice-job-1", LocalDateTime.now());
        analysis.complete("analysis-v1", null, LocalDateTime.now());
        return analysis;
    }

    private VoiceAnalysis analysis(String email) {
        User user = new User(email, "encoded-password");
        ReflectionTestUtils.setField(user, "id", 1L);
        VoiceRecording recording = new VoiceRecording(user, (short) 1, "voice/input.wav", "audio/wav", false);
        ReflectionTestUtils.setField(recording, "id", 20L);
        recording.completeUpload("a".repeat(64), 1024L);
        VoiceAnalysis analysis = new VoiceAnalysis(user, recording, null, VoiceAnalysisMethod.SCALE);
        ReflectionTestUtils.setField(analysis, "id", 10L);
        return analysis;
    }

    private VocalRangeAnalysis range(VoiceAnalysis analysis) {
        return new VocalRangeAnalysis(
                analysis, new BigDecimal("45"), new BigDecimal("74"),
                new BigDecimal("48.5"), new BigDecimal("70.5"), new BigDecimal("59.5"),
                null, null, "{\"bins\":[]}", null, null, null, null,
                null, new BigDecimal("0.92"), null, LocalDateTime.now());
    }

    private VocalTimbreAnalysis timbre(VoiceAnalysis analysis) {
        return new VocalTimbreAnalysis(
                analysis, "맑고 단단한 음색", "{\"bright\":0.8}",
                null, null, new BigDecimal("0.88"), null, null, null,
                "{\"ecapa\":[0.1]}", "embeddings/user-1.json", null,
                null, null, null, null, LocalDateTime.now());
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode errorCode) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
