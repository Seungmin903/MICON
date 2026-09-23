package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;

import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai.recommendation", name = "enabled", havingValue = "true")
public class RecommendationProfileRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(RecommendationProfileRecoveryWorker.class);
    private static final int MAX_DISPATCHES_PER_RUN = 2;
    private static final PageRequest RECOVERY_PAGE = PageRequest.of(0, MAX_DISPATCHES_PER_RUN);

    private final UserVoiceProfileRepository profiles;
    private final RecommendationDispatchService dispatchService;
    private long lastAttemptedProfileId;

    public RecommendationProfileRecoveryWorker(UserVoiceProfileRepository profiles,
                                               RecommendationDispatchService dispatchService) {
        this.profiles = profiles;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${ai.recommendation.recovery.interval:1m}",
            initialDelayString = "${ai.recommendation.recovery.initial-delay:10s}")
    public void recoverMissingRecommendations() {
        var batch = profiles.findMissingCurrentRecommendationsAfterId(lastAttemptedProfileId, RECOVERY_PAGE);
        if (batch.isEmpty()) {
            lastAttemptedProfileId = 0;
            batch = profiles.findMissingCurrentRecommendationsAfterId(0, RECOVERY_PAGE);
        }
        int attempted = 0;
        for (var profile : batch) {
            lastAttemptedProfileId = profile.getId();
            long userId = profile.getUser().getId();
            attempted++;
            try {
                dispatchService.dispatch(userId, profile.getId(), RecommendationMode.OVERALL);
            } catch (RuntimeException exception) {
                log.warn("Recommendation recovery failed. userId={}, profileId={}, profileVersion={}",
                        userId, profile.getId(), profile.getProfileVersion(), exception);
            }
        }
        if (attempted > 0) {
            log.info("Recommendation recovery attempted. lastProfileId={}, attempted={}",
                    lastAttemptedProfileId, attempted);
        }
    }
}
