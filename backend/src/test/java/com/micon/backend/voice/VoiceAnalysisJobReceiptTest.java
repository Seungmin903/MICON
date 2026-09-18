package com.micon.backend.voice;

import com.micon.backend.domain.voice.domain.VoiceAnalysisJob;
import com.micon.backend.domain.voice.domain.VoiceAnalysisSession;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VoiceAnalysisJobReceiptTest {
    @Test void lateReceiptPreservesValidatedCompletion() {
        var job = new VoiceAnalysisJob(mock(VoiceAnalysisSession.class), null, "request", 1);
        var now = LocalDateTime.now();
        job.bindAiJobId("ai-1");
        job.complete(now);
        job.queued("ai-1", "QUEUED", now.plusHours(1), false, now);
        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.getCompletedAt()).isEqualTo(now);
    }
    @Test void duplicateRunningReceiptDoesNotResetProgress() {
        var job = new VoiceAnalysisJob(mock(VoiceAnalysisSession.class), null, "request", 1);
        var now = LocalDateTime.now();
        job.queued("ai-1", "RUNNING", now.plusHours(1), true, now);
        assertThat(job.getStatus()).isEqualTo("RUNNING");
        job.queued("ai-1", "QUEUED", now.plusHours(1), false, now);
        assertThat(job.getStatus()).isEqualTo("RUNNING");
    }
}
