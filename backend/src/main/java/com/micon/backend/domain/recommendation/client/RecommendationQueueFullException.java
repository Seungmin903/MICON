package com.micon.backend.domain.recommendation.client;

/** The AI server rejected admission before creating a job; retrying the BE request is safe. */
public class RecommendationQueueFullException extends RuntimeException {
    public RecommendationQueueFullException(Throwable cause) {
        super("AI recommendation queue is full", cause);
    }
}
