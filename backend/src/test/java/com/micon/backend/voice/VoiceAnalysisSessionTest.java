package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.micon.backend.domain.user.domain.User;
import com.micon.backend.domain.voice.domain.VoiceAnalysisSession;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class VoiceAnalysisSessionTest {
    @Test
    void rangeCompletionAfterTimbreCompletionKeepsSessionReadyToSave() {
        VoiceAnalysisSession session = new VoiceAnalysisSession(
                mock(User.class), null, LocalDateTime.now().plusHours(1));

        session.beginTimbreAnalysis();
        session.acceptTimbreAnalysis();
        session.completeTimbreAnalysis(LocalDateTime.now());
        session.acceptRange("AVAILABLE");

        assertThat(session.getTimbreStatus()).isEqualTo("AVAILABLE");
        assertThat(session.getRangeStatus()).isEqualTo("AVAILABLE");
        assertThat(session.getStatus()).isEqualTo("READY_TO_SAVE");
    }

    @Test
    void timbreCompletionAfterRangeCompletionKeepsSessionReadyToSave() {
        VoiceAnalysisSession session = new VoiceAnalysisSession(
                mock(User.class), null, LocalDateTime.now().plusHours(1));

        session.acceptRange("AVAILABLE");
        session.beginTimbreAnalysis();
        session.acceptTimbreAnalysis();
        session.completeTimbreAnalysis(LocalDateTime.now());

        assertThat(session.getStatus()).isEqualTo("READY_TO_SAVE");
    }
}
