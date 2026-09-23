package com.micon.backend.recommendation;

import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchService;
import com.micon.backend.domain.recommendation.service.RecommendationProfileRecoveryWorker;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.mockito.Mockito.*;

class RecommendationProfileRecoveryWorkerTest {
    @Test void limitsRecoveryDispatchesAndContinuesAfterTheLastAttemptedProfile() {
        var profiles = mock(UserVoiceProfileRepository.class);
        var dispatch = mock(RecommendationDispatchService.class);
        var page = PageRequest.of(0, 2);
        var candidates = List.of(profile(1L), profile(2L));
        var next = profile(3L);
        when(profiles.findMissingCurrentRecommendationsAfterId(0, page)).thenReturn(candidates);
        when(profiles.findMissingCurrentRecommendationsAfterId(2, page)).thenReturn(List.of(next));
        doThrow(new IllegalStateException("temporary outage"))
                .when(dispatch).dispatch(1L, 1L, RecommendationMode.OVERALL);
        var worker = new RecommendationProfileRecoveryWorker(profiles, dispatch);

        worker.recoverMissingRecommendations();
        verify(dispatch, times(2)).dispatch(anyLong(), anyLong(), eq(RecommendationMode.OVERALL));
        verify(dispatch, never()).dispatch(3L, 3L, RecommendationMode.OVERALL);

        worker.recoverMissingRecommendations();
        verify(profiles).findMissingCurrentRecommendationsAfterId(2, page);
        verify(dispatch).dispatch(3L, 3L, RecommendationMode.OVERALL);
    }

    private UserVoiceProfile profile(long id) {
        var profile = mock(UserVoiceProfile.class);
        var user = mock(User.class);
        when(profile.getId()).thenReturn(id);
        when(profile.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(id);
        return profile;
    }
}
