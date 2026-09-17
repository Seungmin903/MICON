package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.micon.backend.voice.application.ArtistSearchJobService;
import com.micon.backend.voice.application.ArtistSearchRetryService;
import com.micon.backend.voice.client.ArtistSearchJobGateway;
import com.micon.backend.voice.domain.VoiceAnalysisJob;
import com.micon.backend.voice.domain.VoiceAnalysisJobRepository;
import com.micon.backend.voice.domain.VoiceAnalysisSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArtistSearchRetryServiceTest {
    private final ArtistSearchJobService jobs = mock(ArtistSearchJobService.class);
    private final ArtistSearchJobGateway gateway = mock(ArtistSearchJobGateway.class);
    private final VoiceAnalysisJobRepository analyses = mock(VoiceAnalysisJobRepository.class);
    private final ArtistSearchRetryService service = new ArtistSearchRetryService(jobs, gateway, analyses);

    @Test
    void submitsANewJobWhenNoPreviousAiJobExists() {
        VoiceAnalysisJob analysis = mock(VoiceAnalysisJob.class);
        VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
        when(analysis.getId()).thenReturn(31L);
        when(analysis.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(7L);
        when(analysis.getRequestId()).thenReturn("timbre-request");
        when(analysis.getStatus()).thenReturn("COMPLETED");
        when(analyses.findById(31L)).thenReturn(Optional.of(analysis));
        var command = new ArtistSearchJobGateway.Command(
                "artist-request", 31L, 7L, "ARTIST_SEARCH", List.of("ARTIST_SEARCH"),
                1, 2, 10, Map.of(), "catalog-v1", "fusion-v2",
                "http://backend/callback", "v1");
        when(jobs.prepareRetry(3L, 31L))
                .thenReturn(new ArtistSearchJobService.RetryPrepared(41L, null, command));
        var accepted = new ArtistSearchJobGateway.Result("artist-job-1", "QUEUED", null, false);
        when(gateway.submit(command)).thenReturn(accepted);

        var response = service.retry(3L, 7L, 31L);

        verify(gateway).submit(command);
        verify(gateway, never()).retry(anyString());
        verify(jobs).queued(41L, accepted);
        assertThat(response.analysisId()).isEqualTo(31L);
    }
}
