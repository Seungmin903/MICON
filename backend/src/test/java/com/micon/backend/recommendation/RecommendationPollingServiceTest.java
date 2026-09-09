package com.micon.backend.recommendation;

import com.micon.backend.recommendation.application.RecommendationPollingService;
import com.micon.backend.recommendation.application.RecommendationResultStore;
import com.micon.backend.recommendation.client.RecommendationResultGateway;
import com.micon.backend.recommendation.config.RecommendationPollingProperties;
import com.micon.backend.recommendation.domain.Recommendation;
import com.micon.backend.recommendation.domain.RecommendationRepository;
import com.micon.backend.recommendation.domain.RecommendationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationPollingServiceTest {
    @Test
    void resumesEveryGeneratingJobAndIsolatesTemporaryFailures() {
        RecommendationRepository recommendations = mock(RecommendationRepository.class);
        RecommendationResultGateway gateway = mock(RecommendationResultGateway.class);
        RecommendationResultStore store = mock(RecommendationResultStore.class);
        Recommendation first = recommendation("job-unavailable");
        Recommendation second = recommendation("job-complete");
        when(recommendations.findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus.GENERATING))
                .thenReturn(List.of(first, second));
        when(gateway.fetch("job-unavailable")).thenThrow(new RuntimeException("timeout"));
        RecommendationResultGateway.Job completed = new RecommendationResultGateway.Job(
                "job-complete", RecommendationResultGateway.Status.COMPLETE, 100, "done", null, null);
        when(gateway.fetch("job-complete")).thenReturn(completed);
        when(store.apply("job-complete", completed)).thenReturn(RecommendationResultStore.Outcome.COMPLETED);
        RecommendationPollingService service = new RecommendationPollingService(
                recommendations, gateway, store, new RecommendationPollingProperties(true));

        RecommendationPollingService.PollSummary summary = service.pollGeneratingRecommendations();

        assertThat(summary.requested()).isEqualTo(2);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.outcomes()).containsEntry(RecommendationResultStore.Outcome.COMPLETED, 1);
    }

    private Recommendation recommendation(String jobId) {
        Recommendation recommendation = mock(Recommendation.class);
        when(recommendation.getJobId()).thenReturn(jobId);
        return recommendation;
    }
}
