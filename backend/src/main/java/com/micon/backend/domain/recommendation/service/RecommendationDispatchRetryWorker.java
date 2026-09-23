package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.recommendation.config.RecommendationAiProperties;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "ai.recommendation", name = "enabled", havingValue = "true")
public class RecommendationDispatchRetryWorker {
    private static final Logger log = LoggerFactory.getLogger(RecommendationDispatchRetryWorker.class);
    private static final int MAX_RETRIES_PER_RUN = 2;
    // Android stops foreground polling after about 45 seconds; leave time for
    // an admitted AI job to finish before then.
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final PageRequest RETRY_PAGE = PageRequest.of(0, MAX_RETRIES_PER_RUN);

    private final RecommendationRepository recommendations;
    private final RecommendationDispatchService dispatchService;
    private final Duration lease;

    public RecommendationDispatchRetryWorker(RecommendationRepository recommendations,
                                             RecommendationDispatchService dispatchService,
                                             RecommendationAiProperties aiProperties) {
        this.recommendations = recommendations;
        this.dispatchService = dispatchService;
        this.lease = aiProperties.readTimeout().plusSeconds(10);
    }

    @Scheduled(fixedDelayString = "${ai.recommendation.dispatch-retry.interval:2s}",
            initialDelayString = "${ai.recommendation.dispatch-retry.initial-delay:5s}")
    public void retryPendingDispatches() {
        LocalDateTime now = LocalDateTime.now();
        var pendingIds = recommendations.findDueDispatchRetryIds(
                RecommendationStatus.GENERATING, now, now.minus(lease), RETRY_PAGE);
        for (Long recommendationId : pendingIds) {
            try {
                dispatchService.retryPending(recommendationId, lease, MAX_WAIT);
            } catch (RuntimeException exception) {
                log.warn("Recommendation dispatch retry worker failed. recommendationId={}",
                        recommendationId, exception);
            }
        }
    }
}
