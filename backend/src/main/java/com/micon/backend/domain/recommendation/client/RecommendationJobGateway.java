package com.micon.backend.domain.recommendation.client;

import com.micon.backend.domain.recommendation.service.RecommendationInput;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;

public interface RecommendationJobGateway {
    Result submit(Command command);

    record Command(Long recommendationId, RecommendationInput input, RecommendationMode mode, int topK) {
        public Command(RecommendationInput input, RecommendationMode mode, int topK) {
            this(null, input, mode, topK);
        }
        public Command {
            if (input == null || mode == null || topK < 1 || topK > 50) {
                throw new IllegalArgumentException("valid input, mode and topK are required");
            }
        }
    }

    record Result(String jobId, String status) {
    }
}
