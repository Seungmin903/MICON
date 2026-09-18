package com.micon.backend.domain.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.client.RecommendationJobGateway;
import com.micon.backend.domain.recommendation.domain.RecommendationMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationDispatchService {
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
                    new RecommendationJobGateway.Command(input, requestedMode, TOP_K));
            if (result == null || result.jobId() == null || result.jobId().isBlank()) {
                throw new IllegalStateException("AI response does not contain jobId");
            }
            lifecycle.assignJob(created.recommendationId(), result.jobId());
            return new RecommendationDispatchResult(
                    created.recommendationId(), result.jobId(), result.status(), created.generatedAt());
        } catch (RuntimeException exception) {
            lifecycle.fail(created.recommendationId(), exception.getMessage());
            throw new BusinessException(ErrorCode.RECOMMENDATION_DISPATCH_FAILED);
        }
    }
}
