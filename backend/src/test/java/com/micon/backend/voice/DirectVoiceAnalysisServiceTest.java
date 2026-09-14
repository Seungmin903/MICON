package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.voice.api.VoiceAnalysisJobResponse;
import com.micon.backend.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.voice.application.DirectVoiceAnalysisService;
import com.micon.backend.voice.application.TimbreAnalysisLifecycleService;
import com.micon.backend.voice.application.VoiceAnalysisUploadValidator;
import com.micon.backend.voice.application.VoiceSessionService;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.ResourceAccessException;

class DirectVoiceAnalysisServiceTest {
    @Test
    void sendsTheOriginalFileAndStableJobMetadataDirectlyToAi() {
        TimbreAnalysisLifecycleService lifecycle = mock(TimbreAnalysisLifecycleService.class);
        VoiceAnalysisUploadValidator validator = mock(VoiceAnalysisUploadValidator.class);
        VoiceAnalysisJobGateway gateway = mock(VoiceAnalysisJobGateway.class);
        VoiceSessionService guestSessions = mock(VoiceSessionService.class);
        MockMultipartFile file = file();
        var request = request("request-1");
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
        var queued = response("request-1", "ai-1", "QUEUED", expiresAt);
        var prepared = new TimbreAnalysisLifecycleService.Prepared(
                31L, 7L, 8L, "request-1", 1, "PENDING", true, response("request-1", null, "PENDING", null));
        when(lifecycle.create(any(Long.class), any(Long.class), any(), any(), any())).thenReturn(prepared);
        var aiResult = new VoiceAnalysisJobGateway.Result("ai-1", "QUEUED", expiresAt, false);
        when(gateway.submit(any())).thenReturn(aiResult);
        when(lifecycle.queued(3L, 31L, aiResult)).thenReturn(queued);
        DirectVoiceAnalysisService service = new DirectVoiceAnalysisService(
                lifecycle, validator, gateway, guestSessions, true, "v1");

        VoiceAnalysisJobResponse result = service.submit(3L, null, 7L, file, request);

        ArgumentCaptor<VoiceAnalysisJobGateway.Command> command =
                ArgumentCaptor.forClass(VoiceAnalysisJobGateway.Command.class);
        verify(gateway).submit(command.capture());
        assertThat(command.getValue().file()).isSameAs(file);
        assertThat(command.getValue().requestId()).isEqualTo("request-1");
        assertThat(command.getValue().components()).containsExactly("ECAPA", "SINGER_BYOL");
        assertThat(command.getValue().contractVersion()).isEqualTo("v1");
        assertThat(result.status()).isEqualTo("QUEUED");
    }

    @Test
    void leavesAmbiguousDispatchReadyForTheSameRequestIdRetry() {
        TimbreAnalysisLifecycleService lifecycle = mock(TimbreAnalysisLifecycleService.class);
        VoiceAnalysisUploadValidator validator = mock(VoiceAnalysisUploadValidator.class);
        VoiceAnalysisJobGateway gateway = mock(VoiceAnalysisJobGateway.class);
        VoiceSessionService guestSessions = mock(VoiceSessionService.class);
        MockMultipartFile file = file();
        var request = request("0199f9f5-7e6a-7a20-bc91-23b8c1ed0a11");
        var prepared = new TimbreAnalysisLifecycleService.Prepared(31L, 7L, 8L,
                request.requestId(), 1, "PENDING", true, response(request.requestId(), null, "PENDING", null));
        when(lifecycle.create(any(Long.class), any(Long.class), any(), any(), any())).thenReturn(prepared);
        when(gateway.submit(any())).thenThrow(new ResourceAccessException("read timed out"));
        DirectVoiceAnalysisService service = new DirectVoiceAnalysisService(
                lifecycle, validator, gateway, guestSessions, true, "v1");

        assertThatThrownBy(() -> service.submit(3L, null, 7L, file, request))
                .isInstanceOf(com.micon.backend.common.exception.BusinessException.class)
                .hasMessageContaining("같은 requestId");
        verify(lifecycle).unknown(3L, 31L, "read timed out");
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "sample.wav", "audio/wav", new byte[] {1});
    }

    private VoiceAnalysisUploadRequest request(String requestId) {
        return new VoiceAnalysisUploadRequest(requestId, VoiceAnalysisMethod.SONG, 21L, "v4",
                LocalDateTime.now(), 30_000, 48_000, "HIGHLIGHT", 10_000, 10_200,
                "inst-v2", "lyrics-v3", List.of());
    }

    private VoiceAnalysisJobResponse response(
            String requestId, String aiJobId, String status, LocalDateTime expiresAt) {
        return new VoiceAnalysisJobResponse(31L, 7L, 8L, requestId, aiJobId, "TIMBRE", status,
                null, null, false, LocalDateTime.now(), null, expiresAt, false, List.of());
    }
}
