package com.micon.backend.domain.recommendation.application;

import com.micon.backend.domain.recommendation.client.RecommendationResultGateway;
import com.micon.backend.domain.recommendation.config.RecommendationPollingProperties;
import com.micon.backend.domain.recommendation.domain.Recommendation;
import com.micon.backend.domain.recommendation.domain.RecommendationRepository;
import com.micon.backend.domain.recommendation.domain.RecommendationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.client.RestClientResponseException;

@Service
public class RecommendationPollingService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationPollingService.class);

    private final RecommendationRepository recommendations;
    private final RecommendationResultGateway gateway;
    private final RecommendationResultStore resultStore;
    private final RecommendationPollingProperties properties;
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private record Retry(int failures, LocalDateTime nextAttempt) { }

    public RecommendationPollingService(RecommendationRepository recommendations,
                                        RecommendationResultGateway gateway,
                                        RecommendationResultStore resultStore,
                                        RecommendationPollingProperties properties) {
        this.recommendations = recommendations;
        this.gateway = gateway;
        this.resultStore = resultStore;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ai.recommendation.polling.interval:1500ms}",
            initialDelayString = "${ai.recommendation.polling.initial-delay:5s}")
    public void scheduledPoll() {
        if (properties.enabled()) {
            pollGeneratingRecommendations();
        }
    }

    public PollSummary pollGeneratingRecommendations() {
        List<Recommendation> pending = recommendations
                .findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus.GENERATING);
        Map<RecommendationResultStore.Outcome, Integer> counts = new EnumMap<>(RecommendationResultStore.Outcome.class);
        int errors = 0;
        var pendingIds = pending.stream().map(Recommendation::getJobId).collect(java.util.stream.Collectors.toSet());
        retries.keySet().retainAll(pendingIds);
        for (Recommendation recommendation : pending) {
            String jobId = recommendation.getJobId();
            LocalDateTime now = LocalDateTime.now();
            try {
                // generatedAt is persisted: restarting the poller cannot reset this deadline.
                if (recommendation.getGeneratedAt() == null ||
                        !now.isBefore(recommendation.getGeneratedAt().plus(properties.maxWait()))) {
                    counts.merge(resultStore.failPending(jobId, "AI_RECOMMENDATION_TIMEOUT",
                            "추천 결과 대기 시간이 초과되었습니다. 다시 요청해 주세요."), 1, Integer::sum);
                    retries.remove(jobId);
                    continue;
                }
                Retry retry = retries.get(jobId);
                if (retry != null && now.isBefore(retry.nextAttempt())) continue;
                RecommendationResultStore.Outcome outcome = resultStore.apply(
                        jobId, gateway.fetch(jobId));
                retries.remove(jobId);
                counts.merge(outcome, 1, Integer::sum);
            } catch (RuntimeException exception) {
                errors++;
                if (permanent(exception)) {
                    counts.merge(resultStore.failPending(jobId, "AI_RESULT_UNAVAILABLE",
                            "추천 결과를 조회할 수 없습니다. 다시 요청해 주세요."), 1, Integer::sum);
                    retries.remove(jobId);
                    continue;
                }
                retries.compute(jobId, (id, old) -> {
                    int failures = old == null ? 1 : Math.min(old.failures() + 1, 6);
                    return new Retry(failures, now.plusSeconds(Math.min(60, 1L << failures)));
                });
                log.warn("Recommendation polling failed temporarily. recommendationId={}, jobId={}",
                        recommendation.getId(), recommendation.getJobId(), exception);
            }
        }
        PollSummary summary = new PollSummary(pending.size(), counts, errors);
        if (!pending.isEmpty()) {
            log.info("Recommendation polling completed. requested={}, outcomes={}, errors={}",
                    summary.requested(), summary.outcomes(), summary.errors());
        }
        return summary;
    }

    private boolean permanent(RuntimeException exception) {
        if (exception instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status >= 400 && status < 500 && status != 408 && status != 429;
        }
        return exception instanceof IllegalArgumentException;
    }

    public record PollSummary(int requested, Map<RecommendationResultStore.Outcome, Integer> outcomes, int errors) {
        public PollSummary {
            outcomes = Map.copyOf(outcomes);
        }
    }
}
