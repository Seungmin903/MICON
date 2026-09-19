package com.micon.backend.recommendation;

import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.service.RecommendationCreationService;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchResult;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchService;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationCreationServiceTest {

    private final UserVoiceProfileRepository profiles = mock(UserVoiceProfileRepository.class);
    private final RecommendationDispatchService dispatch = mock(RecommendationDispatchService.class);
    private final RecommendationCreationService service = new RecommendationCreationService(profiles, dispatch);

    @Test
    void createsRecommendationFromLatestCompletedAnalysis() {
        UserVoiceProfile profile = mock(UserVoiceProfile.class);
        when(profile.getId()).thenReturn(10L);
        when(profiles.findByUserId(1L)).thenReturn(Optional.of(profile));
        RecommendationDispatchResult expected = new RecommendationDispatchResult(
                20L, "job-1", "queued", LocalDateTime.of(2026, 9, 9, 23, 0));
        when(dispatch.dispatch(1L, 10L, RecommendationMode.OVERALL)).thenReturn(expected);

        assertThat(service.createFromLatestAnalysis(1L)).isEqualTo(expected);
        verify(dispatch).dispatch(1L, 10L, RecommendationMode.OVERALL);
    }

    @Test
    void rejectsUserWithoutCompletedAnalysis() {
        when(profiles.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createFromLatestAnalysis(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RECOMMENDATION_INPUT_NOT_READY));
    }
}
