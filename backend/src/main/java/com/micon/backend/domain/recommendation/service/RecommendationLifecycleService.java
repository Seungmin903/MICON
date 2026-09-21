package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.entity.Recommendation;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Created create(long userId, RecommendationInput input, RecommendationMode mode) {
        long profileId = input.profileId();
        UserVoiceProfile profile = profiles.findByIdAndUserIdForUpdate(profileId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        if (profile.getProfileVersion() != input.profileVersion()
                || !profile.getSession().getId().equals(input.sourceSessionId())) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY,
                    "목소리 프로필이 변경됐습니다. 새 프로필로 다시 요청해 주세요.");
        }
        if (recommendations.existsByVoiceProfileIdAndProfileVersionAndSourceSessionIdAndPreferenceVersionAndStatus(
                profileId, input.profileVersion(), input.sourceSessionId(), input.preferenceVersion(), RecommendationStatus.GENERATING)) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_ALREADY_GENERATING);
        }
        Recommendation recommendation = recommendations.saveAndFlush(
                new Recommendation(profile.getUser(), profile, mode, input.preferenceVersion()));
        return new Created(recommendation.getId(), recommendation.getGeneratedAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assignJob(long recommendationId, String jobId) {
        locked(recommendationId).assignJob(jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
