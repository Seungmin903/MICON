package com.micon.backend.domain.voice.application;

import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VoiceAnalysisTimeoutWorker {
    private static final Logger log = LoggerFactory.getLogger(VoiceAnalysisTimeoutWorker.class);
    private final DirectVoiceAnalysisLifecycleService rangeLifecycle;
    private final TimbreAnalysisLifecycleService timbreLifecycle;
    private final Duration maxWait;

    public VoiceAnalysisTimeoutWorker(DirectVoiceAnalysisLifecycleService rangeLifecycle,
                                      TimbreAnalysisLifecycleService timbreLifecycle,
                                      @Value("${ai.voice-analysis-max-wait:10m}") Duration maxWait) {
        this.rangeLifecycle = rangeLifecycle;
        this.timbreLifecycle = timbreLifecycle;
        this.maxWait = maxWait;
    }

    @Scheduled(fixedDelayString = "${ai.voice-analysis-timeout-check-interval:1m}")
    public void failTimedOutJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minus(maxWait);
        int count = rangeLifecycle.failTimedOut(cutoff) + timbreLifecycle.failTimedOut(cutoff);
        if (count > 0) log.warn("Marked {} voice analysis jobs as timed out", count);
    }
}
