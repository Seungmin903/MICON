package com.micon.backend.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.client.RecommendationJobGateway;
import com.micon.backend.recommendation.domain.RecommendationMode;
import org.springframework.stereotype.Service;

@Service
public class RecommendationDispatchService {
    static final int TOP_K = 50;

    private final RecommendationInputAssembler inputs;
    private final RecommendationLifecycleService lifecycle;
    private final RecommendationJobGateway gateway;

    public RecommendationDispatchService(RecommendationInputAssembler inputs,
                                         RecommendationLifecycleService lifecycle,
                                         RecommendationJobGateway gateway) {
        this.inputs = inputs;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
    }

    public RecommendationDispatchResult dispatch(long userId, long voiceAnalysisId, RecommendationMode mode) {
        RecommendationMode requestedMode = mode == null ? RecommendationMode.OVERALL : mode;
        RecommendationInput input = inputs.assemble(userId, voiceAnalysisId);
        RecommendationLifecycleService.Created created = lifecycle.create(userId, voiceAnalysisId, requestedMode);
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
