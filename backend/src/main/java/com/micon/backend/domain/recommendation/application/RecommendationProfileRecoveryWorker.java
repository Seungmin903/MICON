package com.micon.backend.domain.recommendation.application;

import com.micon.backend.domain.recommendation.domain.RecommendationMode;
import com.micon.backend.domain.recommendation.domain.RecommendationRepository;
import com.micon.backend.domain.voice.domain.UserVoiceProfileRepository;
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
    private static final PageRequest LATEST_ONE = PageRequest.of(0, 1);

    private final UserVoiceProfileRepository profiles;
    private final RecommendationRepository recommendations;
    private final RecommendationDispatchService dispatchService;

    public RecommendationProfileRecoveryWorker(UserVoiceProfileRepository profiles,
                                               RecommendationRepository recommendations,
                                               RecommendationDispatchService dispatchService) {
        this.profiles = profiles;
        this.recommendations = recommendations;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${ai.recommendation.recovery.interval:1m}",
            initialDelayString = "${ai.recommendation.recovery.initial-delay:10s}")
    public void recoverMissingRecommendations() {
        profiles.findAll().forEach(profile -> {
            long userId = profile.getUser().getId();
            if (!recommendations.findCurrentByUserId(userId, LATEST_ONE).isEmpty()) {
                return;
            }
            try {
                dispatchService.dispatch(userId, profile.getId(), RecommendationMode.OVERALL);
            } catch (RuntimeException exception) {
                log.warn("Recommendation recovery failed. userId={}, profileId={}, profileVersion={}",
                        userId, profile.getId(), profile.getProfileVersion(), exception);
            }
        });
    }
}
