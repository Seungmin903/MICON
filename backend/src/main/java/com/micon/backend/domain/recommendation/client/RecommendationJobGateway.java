package com.micon.backend.domain.recommendation.client;

import com.micon.backend.domain.recommendation.application.RecommendationInput;
import com.micon.backend.domain.recommendation.domain.RecommendationMode;

public interface RecommendationJobGateway {
    Result submit(Command command);

    record Command(RecommendationInput input, RecommendationMode mode, int topK) {
        public Command {
            if (input == null || mode == null || topK < 1 || topK > 50) {
                throw new IllegalArgumentException("valid input, mode and topK are required");
            }
        }
    }

    record Result(String jobId, String status) {
    }
}
