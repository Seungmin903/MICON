package com.micon.backend.domain.voice.application;

import com.micon.backend.domain.voice.storage.VoiceStorage;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VoiceRecordingDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(VoiceRecordingDeletionWorker.class);
    private final VoiceRecordingDeletionLifecycle lifecycle;
    private final VoiceStorage storage;

    public VoiceRecordingDeletionWorker(VoiceRecordingDeletionLifecycle lifecycle, VoiceStorage storage) {
        this.lifecycle = lifecycle;
        this.storage = storage;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(VoiceRecordingDeletionQueuedEvent event) {
        process(event.deletionJobId());
    }

    @Scheduled(fixedDelayString = "${voice.storage.deletion-retry-interval:30s}")
    public void retryDueJobs() {
        lifecycle.due(LocalDateTime.now()).forEach(this::process);
    }

    public void process(long jobId) {
        VoiceRecordingDeletionLifecycle.Target target = lifecycle.claim(jobId);
        if (target == null) return;
        try {
            storage.delete(target.storagePath());
            lifecycle.complete(jobId);
        } catch (RuntimeException exception) {
            log.warn("Voice recording deletion failed; it will be retried. recordingId={}",
                    target.recordingId(), exception);
            lifecycle.fail(jobId, exception.getMessage());
        }
    }
}
