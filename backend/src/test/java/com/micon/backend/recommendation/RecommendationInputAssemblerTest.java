package com.micon.backend.recommendation;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.application.*;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.domain.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationInputAssemblerTest {
    @Mock UserVoiceProfileRepository profiles;
    @Mock VoiceRangeMeasurementRepository ranges;
    @Mock VoiceModelResultRepository models;
    @Mock RecommendationPreferenceReader preferences;
    RecommendationInputAssembler assembler;

    @BeforeEach void setUp() { assembler = new RecommendationInputAssembler(profiles, ranges, models, preferences,
            new tools.jackson.databind.ObjectMapper()); }

    @Test void sendsBothStoredModelsWithoutRequiringAnyS3Reference() {
        UserVoiceProfile profile = mock(UserVoiceProfile.class);
        User user = mock(User.class);
        VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
        VoiceRangeMeasurement range = mock(VoiceRangeMeasurement.class);
        VoiceModelResult ecapa = model("ECAPA", 192);
        VoiceModelResult byol = model("SINGER_BYOL", 1000);
        when(profile.getUser()).thenReturn(user); when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(profile.getId()).thenReturn(10L); when(profile.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(20L); when(session.getSongId()).thenReturn(30L);
        when(range.getFullLowMidi()).thenReturn(new BigDecimal("48.5"));
        when(range.getFullHighMidi()).thenReturn(new BigDecimal("70.5"));
        when(profiles.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(profile));
        when(ranges.findBySessionId(20L)).thenReturn(Optional.of(range));
        when(models.findBySessionIdAndModelKey(20L, "ECAPA")).thenReturn(Optional.of(ecapa));
        when(models.findBySessionIdAndModelKey(20L, "SINGER_BYOL")).thenReturn(Optional.of(byol));
        when(preferences.findByUserId(1L)).thenReturn(new RecommendationPreferenceReader.Preferences(List.of("록"), List.of("아이유")));

        RecommendationInput input = assembler.assemble(1L, 10L);
        assertThat(input.profileId()).isEqualTo(10L);
        assertThat(input.sourceSongId()).isEqualTo(30L);
        assertThat(input.voiceProfile().stableLowMidi()).isEqualByComparingTo("48.5");
        var embedding = new tools.jackson.databind.ObjectMapper().readTree(input.voiceProfile().userEmbedding());
        assertThat(embedding.get("ECAPA").get("embedding").size()).isEqualTo(192);
        assertThat(embedding.get("SINGER_BYOL").get("embedding").size()).isEqualTo(1000);
        assertThat(embedding.get("SINGER_BYOL").get("segmentInfo").get("embeddings").size()).isEqualTo(1);
        assertThat(input.voiceProfile().embeddingRef()).isNull();
    }

    @Test void rejectsMissingProfile() {
        when(profiles.findByIdAndUserId(10L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> assembler.assemble(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }

    private VoiceModelResult model(String key, int dimension) {
        var json = new tools.jackson.databind.ObjectMapper();
        var vector = new java.util.ArrayList<Double>(java.util.Collections.nCopies(dimension, 0.0));
        vector.set(0, 1.0);
        return new VoiceModelResult(null, 1L, key, "checkpoint-sha256", dimension,
                json.writeValueAsString(vector), "0".repeat(64),
                json.writeValueAsString(java.util.Map.of("segmentCount", 1, "startTimes", List.of(0),
                        "embeddings", List.of(vector))), null);
    }

    @Test void rejectsAMissingSingerModelInsteadOfDispatchingIncompleteInput() {
        var profile = mock(UserVoiceProfile.class);
        var user = mock(User.class);
        var session = mock(VoiceAnalysisSession.class);
        when(profile.getUser()).thenReturn(user);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(profile.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(20L);
        when(profiles.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(profile));
        when(models.findBySessionIdAndModelKey(20L, "ECAPA")).thenReturn(Optional.of(model("ECAPA", 192)));
        assertThatThrownBy(() -> assembler.assemble(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_INPUT_NOT_READY));
    }
}
