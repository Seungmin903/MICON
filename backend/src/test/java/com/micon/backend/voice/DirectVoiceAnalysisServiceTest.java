package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.application.DirectVoiceAnalysisLifecycleService;
import com.micon.backend.voice.application.DirectVoiceAnalysisService;
import com.micon.backend.voice.application.VoiceAnalysisUploadValidator;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class DirectVoiceAnalysisServiceTest {
    @Test
    void sendsTheOriginalFileAndTimelineMetadataDirectlyToAi() {
        DirectVoiceAnalysisLifecycleService lifecycle = mock(DirectVoiceAnalysisLifecycleService.class);
        VoiceAnalysisUploadValidator validator = mock(VoiceAnalysisUploadValidator.class);
        VoiceAnalysisJobGateway gateway = mock(VoiceAnalysisJobGateway.class);
        MockMultipartFile file = new MockMultipartFile("file", "sample.wav", "audio/wav", new byte[] {1});
        var segment = new VoiceAnalysisUploadRequest.Segment(0, 4_000, 10_000, 14_000);
        var request = new VoiceAnalysisUploadRequest("request-1", VoiceAnalysisMethod.SONG, 21L, "v4",
                LocalDateTime.now(), 30_000, 48_000, "HIGHLIGHT", 10_000, 10_200,
                "inst-v2", "lyrics-v3", List.of(segment));
        var queued = new VoiceAnalysisJobResponse(31L, 7L, 8L, "request-1", "ai-1",
                "SINGER_BYOL", "QUEUED", null, null, false, LocalDateTime.now(), null);
        var prepared = new DirectVoiceAnalysisLifecycleService.PreparedJob(31L, "PENDING", null);
        when(lifecycle.createPending(3L, 7L, file, request)).thenReturn(prepared);
        when(gateway.submit(any())).thenReturn(new VoiceAnalysisJobGateway.Result("ai-1", "QUEUED"));
        when(lifecycle.queued(3L, 31L, "ai-1")).thenReturn(queued);
        DirectVoiceAnalysisService service = new DirectVoiceAnalysisService(lifecycle, validator, gateway, true);

        VoiceAnalysisJobResponse result = service.submit(3L, 7L, file, request);

        ArgumentCaptor<VoiceAnalysisJobGateway.Command> command =
                ArgumentCaptor.forClass(VoiceAnalysisJobGateway.Command.class);
        verify(gateway).submit(command.capture());
        assertThat(command.getValue().file()).isSameAs(file);
        assertThat(command.getValue().metadata().requestId()).isEqualTo("request-1");
        assertThat(command.getValue().metadata().segments()).containsExactly(segment);
        assertThat(result.status()).isEqualTo("QUEUED");
    }
}
