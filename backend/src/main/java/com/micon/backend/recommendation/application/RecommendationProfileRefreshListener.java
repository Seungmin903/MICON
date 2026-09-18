package com.micon.backend.recommendation.application;

import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.voice.application.VoiceProfileSavedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RecommendationProfileRefreshListener {
    private static final Logger log = LoggerFactory.getLogger(RecommendationProfileRefreshListener.class);

    private final RecommendationDispatchService dispatchService;

    public RecommendationProfileRefreshListener(RecommendationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refresh(VoiceProfileSavedEvent event) {
        try {
            dispatchService.dispatch(event.userId(), event.profileId(), RecommendationMode.OVERALL);
        } catch (RuntimeException exception) {
            log.warn("Recommendation refresh failed after voice profile save. userId={}, profileId={}, profileVersion={}",
                    event.userId(), event.profileId(), event.profileVersion(), exception);
        }
    }
}
