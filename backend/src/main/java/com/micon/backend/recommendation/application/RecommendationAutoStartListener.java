package com.micon.backend.recommendation.application;

import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.voice.application.VoiceAnalysisCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RecommendationAutoStartListener {
    private static final Logger log = LoggerFactory.getLogger(RecommendationAutoStartListener.class);

    private final RecommendationDispatchService dispatchService;

    public RecommendationAutoStartListener(RecommendationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void start(VoiceAnalysisCompletedEvent event) {
        try {
            dispatchService.dispatch(event.userId(), event.voiceAnalysisId(), RecommendationMode.OVERALL);
        } catch (RuntimeException exception) {
            log.warn("Automatic recommendation dispatch failed. voiceAnalysisId={}, userId={}",
                    event.voiceAnalysisId(), event.userId(), exception);
        }
    }
}
