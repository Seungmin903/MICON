package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.domain.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.domain.voice.client.VoiceStreamGateway;
import com.micon.backend.domain.voice.dto.VoiceAnalysisJobResponse;
import com.micon.backend.domain.voice.dto.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.entity.VoiceAnalysisMethod;
import com.micon.backend.domain.voice.service.StreamVoiceAnalysisService;
import com.micon.backend.domain.voice.service.TimbreAnalysisLifecycleService;
import com.micon.backend.domain.voice.service.VoiceAnalysisUploadValidator;
import com.micon.backend.domain.voice.service.VoiceSessionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class StreamVoiceAnalysisServiceTest {
    private static final String ID = "0199f9f5-7e6a-7a20-bc91-23b8c1ed0a11";
    private static final String SHA = "a".repeat(64);
    private final VoiceSessionService sessions = mock(VoiceSessionService.class);
    private final TimbreAnalysisLifecycleService lifecycle = mock(TimbreAnalysisLifecycleService.class);
    private final VoiceAnalysisUploadValidator validator = mock(VoiceAnalysisUploadValidator.class);
    private final VoiceStreamGateway ai = mock(VoiceStreamGateway.class);
    private final StreamVoiceAnalysisService service =
            new StreamVoiceAnalysisService(sessions, lifecycle, validator, ai, true, true, "v1");

    @Test
    void forwardsChunksOnlyForTheOwnedSession() {
        byte[] pcm = new byte[] {1, 2};
        when(ai.chunk(ID, 7L, 0, pcm)).thenReturn(new VoiceStreamGateway.State(ID, 7L, 48000, 1, 2, false));
        assertThat(service.chunk(3L, null, 7L, ID, 0, pcm).nextIndex()).isEqualTo(1);
        verify(sessions).requireAccessible(3L, null, 7L);
        assertThatThrownBy(() -> service.chunk(3L, null, 7L, ID, 1, new byte[192001]))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void commitsOnlyAfterAiVerifiesTheCompletePcm() {
        VoiceAnalysisUploadRequest request = request();
        VoiceStreamGateway.Inspected inspected =
                new VoiceStreamGateway.Inspected(ID, 7L, 48000, 5, 960000, false, SHA, 480000, 10000);
        when(ai.inspect(ID, 7L, SHA)).thenReturn(inspected);
        VoiceAnalysisJobResponse pending = response(null, "PENDING");
        var prepared = new TimbreAnalysisLifecycleService.Prepared(31L, 7L, 8L,
                ID, 1, "PENDING", true, pending);
        when(lifecycle.createStream(3L, 7L, request, SHA, 960044)).thenReturn(prepared);
        var accepted = new VoiceAnalysisJobGateway.Result("job-1", "QUEUED", LocalDateTime.now().plusHours(1), false);
        when(ai.complete(ID, SHA, 31L, 7L, 8L, 1, "v1", request)).thenReturn(accepted);
        when(lifecycle.queued(3L, 31L, accepted)).thenReturn(response("job-1", "QUEUED"));

        assertThat(service.complete(3L, null, 7L, ID, SHA, request).status()).isEqualTo("QUEUED");
        verify(validator).validateStream(request, 960000, 48000, 10000);
        verify(lifecycle).queued(3L, 31L, accepted);
    }

    @Test
    void ambiguousAiReplyRetainsTheSameRequestForRetry() {
        VoiceAnalysisUploadRequest request = request();
        when(ai.inspect(ID, 7L, SHA)).thenReturn(
                new VoiceStreamGateway.Inspected(ID, 7L, 48000, 5, 960000, false, SHA, 480000, 10000));
        when(lifecycle.createStream(any(Long.class), any(Long.class), any(), any(), any(Long.class)))
                .thenReturn(new TimbreAnalysisLifecycleService.Prepared(31L, 7L, 8L,
                        ID, 1, "PENDING", true, response(null, "PENDING")));
        when(ai.complete(ID, SHA, 31L, 7L, 8L, 1, "v1", request))
                .thenThrow(new ResourceAccessException("connection switched"));

        assertThatThrownBy(() -> service.complete(3L, null, 7L, ID, SHA, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("같은 streamId");
        verify(lifecycle).unknown(3L, 31L, "connection switched");
    }

    @Test
    void lostCompletionReplyUsesThePersistedJobEvenAfterDraftExpiry() {
        VoiceAnalysisJobResponse queued = response("job-1", "QUEUED");
        when(lifecycle.latest(3L, 7L)).thenReturn(queued);

        assertThat(service.complete(3L, null, 7L, ID, SHA, request())).isSameAs(queued);
        verifyNoInteractions(ai);
    }

    private VoiceAnalysisUploadRequest request() {
        return new VoiceAnalysisUploadRequest(ID, VoiceAnalysisMethod.SONG, "21", "holo-v1",
                LocalDateTime.now(), 10000, 48000, "BEGINNING", 0, 0,
                "v1", "v1", List.of(new VoiceAnalysisUploadRequest.Segment(0, 10000, 0, 10000)));
    }

    private VoiceAnalysisJobResponse response(String jobId, String status) {
        return new VoiceAnalysisJobResponse(31L, 7L, 8L, ID, jobId, "TIMBRE", status,
                null, null, false, LocalDateTime.now(), null, LocalDateTime.now().plusHours(1),
                false, List.of());
    }
}
