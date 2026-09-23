package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.recommendation.client.RecommendationResultGateway;
import com.micon.backend.domain.recommendation.config.RecommendationPollingProperties;
import com.micon.backend.domain.recommendation.entity.Recommendation;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class RecommendationPollingService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationPollingService.class);

    private final RecommendationRepository recommendations;
    private final RecommendationResultGateway gateway;
    private final RecommendationResultStore resultStore;
    private final RecommendationPollingProperties properties;
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private record Retry(int failures, LocalDateTime nextAttempt) { }
    private record PendingJob(Long recommendationId, String jobId, LocalDateTime generatedAt) { }
    private record PollResult(RecommendationResultStore.Outcome outcome, int errors) { }

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
        long startedAt = System.nanoTime();
        List<Recommendation> pending = recommendations
                .findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus.GENERATING);
        List<PendingJob> jobs = pending.stream()
                .map(recommendation -> new PendingJob(recommendation.getId(), recommendation.getJobId(),
                        recommendation.getGeneratedAt()))
                .toList();
        Map<RecommendationResultStore.Outcome, Integer> counts = new EnumMap<>(RecommendationResultStore.Outcome.class);
        int errors = 0;
        var pendingIds = jobs.stream().map(PendingJob::jobId).collect(java.util.stream.Collectors.toSet());
        retries.keySet().retainAll(pendingIds);
        // Each HTTP fetch is blocking. Bound concurrency so one slow AI response does not
        // delay every other job, while avoiding an unbounded request burst against AI.
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 0; start < jobs.size(); start += properties.maxConcurrent()) {
                List<Future<PollResult>> batch = new ArrayList<>();
                for (PendingJob job : jobs.subList(start, Math.min(start + properties.maxConcurrent(), jobs.size()))) {
                    batch.add(workers.submit(() -> pollOne(job)));
                }
                for (Future<PollResult> future : batch) {
                    try {
                        PollResult result = future.get();
                        errors += result.errors();
                        if (result.outcome() != null) counts.merge(result.outcome(), 1, Integer::sum);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Recommendation polling interrupted", exception);
                    } catch (ExecutionException exception) {
                        errors++;
                        log.error("Recommendation polling worker failed", exception.getCause());
                    }
                }
            }
        }
        PollSummary summary = new PollSummary(jobs.size(), counts, errors);
        if (!jobs.isEmpty()) {
            log.info("Recommendation polling completed. requested={}, outcomes={}, errors={}, durationMs={}",
                    summary.requested(), summary.outcomes(), summary.errors(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
        return summary;
    }

    private PollResult pollOne(PendingJob recommendation) {
        String jobId = recommendation.jobId();
        LocalDateTime now = LocalDateTime.now();
        try {
            // generatedAt is persisted: restarting the poller cannot reset this deadline.
            if (recommendation.generatedAt() == null ||
                    !now.isBefore(recommendation.generatedAt().plus(properties.maxWait()))) {
                RecommendationResultStore.Outcome outcome = resultStore.failPending(jobId,
                        "AI_RECOMMENDATION_TIMEOUT", "추천 결과 대기 시간이 초과되었습니다. 다시 요청해 주세요.");
                retries.remove(jobId);
                return new PollResult(outcome, 0);
            }
            Retry retry = retries.get(jobId);
            if (retry != null && now.isBefore(retry.nextAttempt())) return new PollResult(null, 0);
            RecommendationResultStore.Outcome outcome = resultStore.apply(jobId, gateway.fetch(jobId));
            retries.remove(jobId);
            return new PollResult(outcome, 0);
        } catch (RuntimeException exception) {
            if (permanent(exception)) {
                try {
                    RecommendationResultStore.Outcome outcome = resultStore.failPending(jobId,
                            "AI_RESULT_UNAVAILABLE", "추천 결과를 조회할 수 없습니다. 다시 요청해 주세요.");
                    retries.remove(jobId);
                    return new PollResult(outcome, 1);
                } catch (RuntimeException storeException) {
                    exception.addSuppressed(storeException);
                }
            }
            retries.compute(jobId, (id, old) -> {
                int failures = old == null ? 1 : Math.min(old.failures() + 1, 6);
                return new Retry(failures, now.plusSeconds(Math.min(60, 1L << failures)));
            });
            log.warn("Recommendation polling failed temporarily. recommendationId={}, jobId={}",
                    recommendation.recommendationId(), jobId, exception);
            return new PollResult(null, 1);
        }
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
