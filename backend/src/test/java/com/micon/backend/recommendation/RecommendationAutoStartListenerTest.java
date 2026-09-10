package com.micon.backend.recommendation;

import com.micon.backend.recommendation.application.RecommendationAutoStartListener;
import com.micon.backend.recommendation.application.RecommendationDispatchService;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.voice.application.VoiceAnalysisCompletedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RecommendationAutoStartListenerTest {

    @Test
    void startsOverallRecommendationForCompletedAnalysis() {
        RecommendationDispatchService dispatch = mock(RecommendationDispatchService.class);
        RecommendationAutoStartListener listener = new RecommendationAutoStartListener(dispatch);

        listener.start(new VoiceAnalysisCompletedEvent(10L, 1L));

        verify(dispatch).dispatch(1L, 10L, RecommendationMode.OVERALL);
    }

    @Test
    void doesNotPropagateRecommendationFailureToAnalysisCallback() {
        RecommendationDispatchService dispatch = mock(RecommendationDispatchService.class);
        doThrow(new RuntimeException("AI unavailable"))
                .when(dispatch).dispatch(1L, 10L, RecommendationMode.OVERALL);
        RecommendationAutoStartListener listener = new RecommendationAutoStartListener(dispatch);

        assertThatCode(() -> listener.start(new VoiceAnalysisCompletedEvent(10L, 1L)))
                .doesNotThrowAnyException();
    }
}
