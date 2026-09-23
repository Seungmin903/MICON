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
import java.time.Duration;
import java.util.Optional;

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
    public void scheduleDispatchRetry(long recommendationId, LocalDateTime nextAttempt) {
        locked(recommendationId).scheduleDispatchRetry(nextAttempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PendingDispatch> claimDispatchRetry(long recommendationId, LocalDateTime now,
                                                        Duration lease, Duration maxWait) {
        Recommendation recommendation = locked(recommendationId);
        if (recommendation.getStatus() != RecommendationStatus.GENERATING || recommendation.getJobId() != null) {
            return Optional.empty();
        }
        if (recommendation.getDispatchRetryAt() == null) {
            // A crash before the first AI response is ambiguous: AI may already have
            // accepted a job. Do not submit a duplicate without an idempotency key.
            if (recommendation.getGeneratedAt().isBefore(now.minus(lease))) {
                recommendation.fail("AI_DISPATCH_UNCONFIRMED", "AI 접수 여부를 확인할 수 없어 다시 요청이 필요합니다.", now);
            }
            return Optional.empty();
        }
        // Another worker may hold a lease even when the overall deadline has passed.
        // Do not fail its in-flight submission from a stale candidate list.
        if (recommendation.getDispatchRetryAt().isAfter(now)) return Optional.empty();
        if (!now.isBefore(recommendation.getGeneratedAt().plus(maxWait))) {
            recommendation.fail("AI_CAPACITY_TIMEOUT", "추천 서버 대기열이 계속 가득 차 다시 요청이 필요합니다.", now);
            return Optional.empty();
        }
        if (!recommendation.claimDispatchRetry(now, now.plus(lease))) {
            return Optional.empty();
        }
        return Optional.of(new PendingDispatch(recommendation.getId(), recommendation.getUser().getId(),
                recommendation.getVoiceProfile().getId(), recommendation.getSourceSessionId(),
                recommendation.getProfileVersion(), recommendation.getPreferenceVersion(),
                recommendation.getMode(), recommendation.getDispatchAttempts()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long recommendationId, String reason) {
        fail(recommendationId, "AI_DISPATCH_FAILED", reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long recommendationId, String code, String reason) {
        locked(recommendationId).fail(code, abbreviate(reason), LocalDateTime.now());
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

    public record PendingDispatch(long recommendationId, long userId, long profileId,
                                  Long sourceSessionId, Long profileVersion, Long preferenceVersion,
                                  RecommendationMode mode, int attempts) {
        public boolean matches(RecommendationInput input) {
            return profileId == input.profileId()
                    && java.util.Objects.equals(sourceSessionId, input.sourceSessionId())
                    && java.util.Objects.equals(profileVersion, input.profileVersion())
                    && java.util.Objects.equals(preferenceVersion, input.preferenceVersion());
        }
    }
}
