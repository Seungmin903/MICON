package com.micon.backend.voice.application;

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
    private final DirectVoiceAnalysisLifecycleService lifecycle;
    private final Duration maxWait;

    public VoiceAnalysisTimeoutWorker(DirectVoiceAnalysisLifecycleService lifecycle,
                                      @Value("${ai.voice-analysis-max-wait:10m}") Duration maxWait) {
        this.lifecycle = lifecycle;
        this.maxWait = maxWait;
    }

    @Scheduled(fixedDelayString = "${ai.voice-analysis-timeout-check-interval:1m}")
    public void failTimedOutJobs() {
        int count = lifecycle.failTimedOut(LocalDateTime.now().minus(maxWait));
        if (count > 0) log.warn("Marked {} voice analysis jobs as timed out", count);
    }
}
