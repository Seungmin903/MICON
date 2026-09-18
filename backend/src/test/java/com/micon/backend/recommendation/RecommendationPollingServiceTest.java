package com.micon.backend.recommendation;

import com.micon.backend.domain.recommendation.application.RecommendationPollingService;
import com.micon.backend.domain.recommendation.application.RecommendationResultStore;
import com.micon.backend.domain.recommendation.client.RecommendationResultGateway;
import com.micon.backend.domain.recommendation.config.RecommendationPollingProperties;
import com.micon.backend.domain.recommendation.domain.Recommendation;
import com.micon.backend.domain.recommendation.domain.RecommendationRepository;
import com.micon.backend.domain.recommendation.domain.RecommendationStatus;
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
        when(recommendation.getGeneratedAt()).thenReturn(java.time.LocalDateTime.now());
        return recommendation;
    }

    @Test void persistedDeadlineEndsPollingEvenWhenTheAiNeverResponds() {
        var repository = mock(RecommendationRepository.class);
        var gateway = mock(RecommendationResultGateway.class);
        var store = mock(RecommendationResultStore.class);
        var pending = recommendation("old-job");
        when(pending.getGeneratedAt()).thenReturn(java.time.LocalDateTime.now().minusMinutes(11));
        when(repository.findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus.GENERATING))
                .thenReturn(List.of(pending));
        when(store.failPending(org.mockito.ArgumentMatchers.eq("old-job"),
                org.mockito.ArgumentMatchers.eq("AI_RECOMMENDATION_TIMEOUT"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(RecommendationResultStore.Outcome.FAILED);
        var result = new RecommendationPollingService(repository, gateway, store,
                new RecommendationPollingProperties(true)).pollGeneratingRecommendations();
        assertThat(result.outcomes()).containsEntry(RecommendationResultStore.Outcome.FAILED, 1);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test void permanentNotFoundEndsTheJob() {
        var repository = mock(RecommendationRepository.class);
        var gateway = mock(RecommendationResultGateway.class);
        var store = mock(RecommendationResultStore.class);
        var pending = recommendation("missing-job");
        when(repository.findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus.GENERATING))
                .thenReturn(List.of(pending));
        when(gateway.fetch("missing-job")).thenThrow(new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        when(store.failPending(org.mockito.ArgumentMatchers.eq("missing-job"),
                org.mockito.ArgumentMatchers.eq("AI_RESULT_UNAVAILABLE"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(RecommendationResultStore.Outcome.FAILED);
        var result = new RecommendationPollingService(repository, gateway, store,
                new RecommendationPollingProperties(true)).pollGeneratingRecommendations();
        assertThat(result.outcomes()).containsEntry(RecommendationResultStore.Outcome.FAILED, 1);
    }
}
