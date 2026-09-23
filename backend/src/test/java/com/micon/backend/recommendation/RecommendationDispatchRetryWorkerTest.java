package com.micon.backend.recommendation;

import com.micon.backend.domain.recommendation.config.RecommendationAiProperties;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchRetryWorker;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RecommendationDispatchRetryWorkerTest {
    @Test void limitsRetriesAndKeepsProcessingAfterOneFailure() {
        var recommendations = mock(RecommendationRepository.class);
        var dispatch = mock(RecommendationDispatchService.class);
        when(recommendations.findDueDispatchRetryIds(eq(RecommendationStatus.GENERATING),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("temporary database failure"))
                .when(dispatch).retryPending(1L, Duration.ofSeconds(40), Duration.ofSeconds(30));
        var properties = new RecommendationAiProperties("http://localhost:8788", "",
                Duration.ofSeconds(3), Duration.ofSeconds(30));
        var worker = new RecommendationDispatchRetryWorker(recommendations, dispatch, properties);

        worker.retryPendingDispatches();

        verify(dispatch).retryPending(1L, Duration.ofSeconds(40), Duration.ofSeconds(30));
        verify(dispatch).retryPending(2L, Duration.ofSeconds(40), Duration.ofSeconds(30));
    }
}
