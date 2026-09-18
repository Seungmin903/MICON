package com.micon.backend.domain.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.domain.Recommendation;
import com.micon.backend.domain.recommendation.domain.RecommendationMode;
import com.micon.backend.domain.recommendation.domain.RecommendationRepository;
import com.micon.backend.domain.recommendation.domain.RecommendationStatus;
import com.micon.backend.voice.domain.UserVoiceProfile;
import com.micon.backend.voice.domain.UserVoiceProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RecommendationLifecycleService {
    private final UserVoiceProfileRepository profiles;
    private final RecommendationRepository recommendations;

    public RecommendationLifecycleService(UserVoiceProfileRepository profiles,
                                          RecommendationRepository recommendations) {
        this.profiles = profiles;
        this.recommendations = recommendations;
    }

    @Transactional
    public Created create(long userId, long profileId, RecommendationMode mode) {
        UserVoiceProfile profile = profiles.findByIdAndUserId(profileId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (recommendations.existsByVoiceProfileIdAndStatus(profileId, RecommendationStatus.GENERATING)) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_ALREADY_GENERATING);
        }
        Recommendation recommendation = recommendations.saveAndFlush(
                new Recommendation(profile.getUser(), profile, mode));
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
