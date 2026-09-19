package com.micon.backend.recommendation;

import com.micon.backend.domain.recommendation.service.RecommendationDispatchService;
import com.micon.backend.domain.recommendation.service.RecommendationProfileRefreshListener;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.voice.service.VoiceProfileSavedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationProfileRefreshListenerTest {

    @Test
    void createsRecommendationForSavedProfile() {
        RecommendationDispatchService dispatch = mock(RecommendationDispatchService.class);
        RecommendationProfileRefreshListener listener = new RecommendationProfileRefreshListener(dispatch);

        listener.refresh(new VoiceProfileSavedEvent(1L, 10L, 2L));

        verify(dispatch).dispatch(1L, 10L, RecommendationMode.OVERALL);
    }

    @Test
    void doesNotFailProfileSaveWhenRecommendationDispatchFails() {
        RecommendationDispatchService dispatch = mock(RecommendationDispatchService.class);
        RecommendationProfileRefreshListener listener = new RecommendationProfileRefreshListener(dispatch);
        when(dispatch.dispatch(1L, 10L, RecommendationMode.OVERALL))
                .thenThrow(new IllegalStateException("recommendation server unavailable"));

        listener.refresh(new VoiceProfileSavedEvent(1L, 10L, 2L));

        verify(dispatch).dispatch(1L, 10L, RecommendationMode.OVERALL);
    }
}
