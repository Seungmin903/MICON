package com.micon.backend.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.domain.Recommendation;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.recommendation.domain.RecommendationRepository;
import com.micon.backend.recommendation.domain.RecommendationStatus;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RecommendationLifecycleService {
    private final VoiceAnalysisRepository analyses;
    private final RecommendationRepository recommendations;

    public RecommendationLifecycleService(VoiceAnalysisRepository analyses,
                                          RecommendationRepository recommendations) {
        this.analyses = analyses;
        this.recommendations = recommendations;
    }

    @Transactional
    public Created create(long userId, long voiceAnalysisId, RecommendationMode mode) {
        VoiceAnalysis analysis = analyses.findByIdAndUserIdForUpdate(voiceAnalysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (recommendations.existsByVoiceAnalysisIdAndStatus(voiceAnalysisId, RecommendationStatus.GENERATING)) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_ALREADY_GENERATING);
        }
        Recommendation recommendation = recommendations.saveAndFlush(
                new Recommendation(analysis.getUser(), analysis, mode));
        return new Created(recommendation.getId(), recommendation.getGeneratedAt());
    }

    @Transactional
    public void assignJob(long recommendationId, String jobId) {
        locked(recommendationId).assignJob(jobId);
    }

    @Transactional
    public void fail(long recommendationId, String reason) {
        locked(recommendationId).fail("AI_DISPATCH_FAILED", abbreviate(reason), LocalDateTime.now());
    }

    private Recommendation locked(long recommendationId) {
        return recommendations.findByIdForUpdate(recommendationId)
                .orElseThrow(() -> new IllegalStateException("recommendation not found: " + recommendationId));
    }

    private String abbreviate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "AI recommendation request failed";
        }
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }

    public record Created(long recommendationId, LocalDateTime generatedAt) {
    }
}
