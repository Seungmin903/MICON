package com.micon.backend.domain.recommendation.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.client.RecommendationJobGateway;
import com.micon.backend.domain.recommendation.client.RecommendationQueueFullException;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class RecommendationDispatchService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationDispatchService.class);
    static final int TOP_K = 50;

    private final RecommendationInputAssembler inputs;
    private final RecommendationLifecycleService lifecycle;
    private final RecommendationJobGateway gateway;
    private final boolean enabled;

    public RecommendationDispatchService(RecommendationInputAssembler inputs,
                                         RecommendationLifecycleService lifecycle,
                                         RecommendationJobGateway gateway,
                                         @Value("${ai.recommendation.enabled:false}") boolean enabled) {
        this.inputs = inputs;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.enabled = enabled;
    }

    // AFTER_COMMIT may still have the completed EntityManager bound to this thread.
    // Suspend it, and never hold a database transaction open across the AI request.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RecommendationDispatchResult dispatch(long userId, long profileId, RecommendationMode mode) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_DISPATCH_FAILED);
        }
        RecommendationMode requestedMode = mode == null ? RecommendationMode.OVERALL : mode;
        RecommendationInput input = inputs.assemble(userId, profileId);
        RecommendationLifecycleService.Created created = lifecycle.create(userId, input, requestedMode);
        try {
            RecommendationJobGateway.Result result = gateway.submit(
                    new RecommendationJobGateway.Command(created.recommendationId(), input, requestedMode, TOP_K));
            if (result == null || result.jobId() == null || result.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            lifecycle.assignJob(created.recommendationId(), result.jobId());
            return new RecommendationDispatchResult(
                    created.recommendationId(), result.jobId(), result.status(), created.generatedAt());
        } catch (RecommendationQueueFullException exception) {
            lifecycle.scheduleDispatchRetry(created.recommendationId(), LocalDateTime.now().plusSeconds(1));
            log.info("Recommendation waiting for AI queue capacity. recommendationId={}", created.recommendationId());
            return new RecommendationDispatchResult(created.recommendationId(), null, "queued", created.generatedAt());
        } catch (RuntimeException exception) {
            lifecycle.fail(created.recommendationId(), exception.getMessage());
            throw new BusinessException(ErrorCode.RECOMMENDATION_DISPATCH_FAILED);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retryPending(long recommendationId, Duration lease, Duration maxWait) {
        var claimed = lifecycle.claimDispatchRetry(recommendationId, LocalDateTime.now(), lease, maxWait);
        if (claimed.isEmpty()) return;
        var pending = claimed.orElseThrow();
        try {
            RecommendationInput input = inputs.assemble(pending.userId(), pending.profileId());
            if (!pending.matches(input)) {
                lifecycle.fail(recommendationId, "PROFILE_SUPERSEDED", "추천 입력이 변경됐습니다.");
                return;
            }
            RecommendationJobGateway.Result result = gateway.submit(
                    new RecommendationJobGateway.Command(recommendationId, input, pending.mode(), TOP_K));
            if (result == null || result.jobId() == null || result.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            lifecycle.assignJob(recommendationId, result.jobId());
            log.info("Recommendation admitted after retry. recommendationId={}, jobId={}, attempts={}",
                    recommendationId, result.jobId(), pending.attempts());
        } catch (RecommendationQueueFullException exception) {
            long delaySeconds = Math.min(15, 1L << Math.min(pending.attempts(), 4));
            lifecycle.scheduleDispatchRetry(recommendationId, LocalDateTime.now().plusSeconds(delaySeconds));
            log.info("Recommendation AI queue still full. recommendationId={}, attempts={}, nextRetrySeconds={}",
                    recommendationId, pending.attempts(), delaySeconds);
        } catch (RuntimeException exception) {
            lifecycle.fail(recommendationId, exception.getMessage());
            log.warn("Recommendation dispatch retry failed. recommendationId={}", recommendationId, exception);
        }
    }
}
