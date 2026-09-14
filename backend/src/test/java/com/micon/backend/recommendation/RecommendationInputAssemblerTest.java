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

    @BeforeEach void setUp() { assembler = new RecommendationInputAssembler(profiles, ranges, models, preferences); }

    @Test void assemblesSavedProfileUsingEcapaReference() {
        UserVoiceProfile profile = mock(UserVoiceProfile.class);
        User user = mock(User.class);
        VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
        VoiceRangeMeasurement range = mock(VoiceRangeMeasurement.class);
        VoiceModelResult ecapa = mock(VoiceModelResult.class);
        when(profile.getUser()).thenReturn(user); when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(profile.getId()).thenReturn(10L); when(profile.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(20L); when(session.getSongId()).thenReturn(30L);
        when(range.getFullLowMidi()).thenReturn(new BigDecimal("48.5"));
        when(range.getFullHighMidi()).thenReturn(new BigDecimal("70.5"));
        when(ecapa.getVectorObjectKey()).thenReturn("embeddings/user-1.bin");
        when(profiles.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(profile));
        when(ranges.findBySessionId(20L)).thenReturn(Optional.of(range));
        when(models.findBySessionIdAndModelKey(20L, "ECAPA")).thenReturn(Optional.of(ecapa));
        when(preferences.findByUserId(1L)).thenReturn(new RecommendationPreferenceReader.Preferences(List.of("록"), List.of("아이유")));

        RecommendationInput input = assembler.assemble(1L, 10L);
        assertThat(input.profileId()).isEqualTo(10L);
        assertThat(input.sourceSongId()).isEqualTo(30L);
        assertThat(input.voiceProfile().stableLowMidi()).isEqualByComparingTo("48.5");
        assertThat(input.voiceProfile().embeddingRef()).isEqualTo("embeddings/user-1.bin");
    }

    @Test void rejectsMissingProfile() {
        when(profiles.findByIdAndUserId(10L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> assembler.assemble(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
    }
}
