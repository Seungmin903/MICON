package com.micon.backend.voice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.domain.voice.application.VoiceRecordingDeletionLifecycle;
import com.micon.backend.domain.voice.application.VoiceRecordingDeletionWorker;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import org.junit.jupiter.api.Test;

class VoiceRecordingDeletionWorkerTest {
    private final VoiceRecordingDeletionLifecycle lifecycle = mock(VoiceRecordingDeletionLifecycle.class);
    private final VoiceStorage storage = mock(VoiceStorage.class);
    private final VoiceRecordingDeletionWorker worker = new VoiceRecordingDeletionWorker(lifecycle, storage);

    @Test
    void deletesStorageAfterAJobHasBeenClaimedAndThenCompletesTheDatabaseJob() {
        when(lifecycle.claim(3L))
                .thenReturn(new VoiceRecordingDeletionLifecycle.Target(3L, 9L, "voice/9.wav"));

        worker.process(3L);

        verify(storage).delete("voice/9.wav");
        verify(lifecycle).complete(3L);
    }

    @Test
    void recordsFailureForAReusableRetryInsteadOfThrowing() {
        when(lifecycle.claim(3L))
                .thenReturn(new VoiceRecordingDeletionLifecycle.Target(3L, 9L, "voice/9.wav"));
        org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).delete("voice/9.wav");

        worker.process(3L);

        verify(lifecycle).fail(3L, "storage unavailable");
    }
}
