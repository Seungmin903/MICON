package com.micon.backend.voice;

import com.micon.backend.domain.voice.repository.VoiceAnalysisComponentJobRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisJobRepository;
import com.micon.backend.domain.voice.repository.VoiceModelResultRepository;
import com.micon.backend.domain.voice.repository.VoiceRangeMeasurementRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.domain.voice.dto.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.domain.voice.service.VoiceAnalysisComponentCallbackService;
import com.micon.backend.domain.voice.service.VoiceSessionService;
import com.micon.backend.domain.voice.service.ArtistSearchCallbackService;
import com.micon.backend.domain.voice.service.ArtistSearchRequested;
import com.micon.backend.domain.voice.entity.VoiceAnalysisComponentJob;
import com.micon.backend.domain.voice.entity.VoiceAnalysisJob;
import com.micon.backend.domain.voice.entity.VoiceAnalysisSession;
import com.micon.backend.domain.voice.entity.VoiceModelResult;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class VoiceAnalysisComponentCallbackServiceTest {
    private final VoiceAnalysisComponentJobRepository jobs = mock(VoiceAnalysisComponentJobRepository.class);
    private final VoiceAnalysisJobRepository parentJobs = mock(VoiceAnalysisJobRepository.class);
    private final VoiceModelResultRepository results = mock(VoiceModelResultRepository.class);
    private final VoiceSessionService voiceSessions = mock(VoiceSessionService.class);
    private final VoiceAnalysisComponentJob job = mock(VoiceAnalysisComponentJob.class);
    private final VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
    private final VoiceAnalysisJob parent = mock(VoiceAnalysisJob.class);
    private final ArtistSearchCallbackService artistSearchCallbacks = mock(ArtistSearchCallbackService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final VoiceAnalysisComponentCallbackService service = new VoiceAnalysisComponentCallbackService(
            jobs, results, new ObjectMapper(), mock(VoiceRangeMeasurementRepository.class),
            voiceSessions, parentJobs, artistSearchCallbacks, events);

    @BeforeEach
    void setUp() {
        when(parentJobs.findByRequestIdForUpdate("request-1")).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(31L);
        when(parent.getSession()).thenReturn(session);
        when(parent.getAttemptNo()).thenReturn(1);
        when(parent.getStatus()).thenReturn("COMPLETED");
        when(jobs.findByAnalysisJobIdForUpdate(31L)).thenReturn(java.util.List.of(job));
        when(job.getId()).thenReturn(11L);
        when(job.getSession()).thenReturn(session);
        when(job.getAnalysisJob()).thenReturn(parent);
        when(job.getComponentType()).thenReturn("SINGER_BYOL");
        when(job.getAttemptNo()).thenReturn(1);
        when(job.isTerminal()).thenReturn(false);
        when(job.getStatus()).thenReturn("COMPLETED");
        when(session.getId()).thenReturn(7L);
        when(results.findBySessionIdAndModelKey(7L, "SINGER_BYOL")).thenReturn(Optional.empty());
    }

    @Test
    void storesOnlyACompleteOneThousandDimensionSingerByolEmbedding() {
        var response = service.accept(completed(Collections.nCopies(1000, 0.125), 1000));

        ArgumentCaptor<VoiceModelResult> captor = ArgumentCaptor.forClass(VoiceModelResult.class);
        verify(results).save(captor.capture());
        assertThat(captor.getValue().getDimension()).isEqualTo(1000);
        assertThat(captor.getValue().getVectorJson()).isNotBlank();
        verify(job).complete(any(), any(), any());
        verify(session).completeTimbreAnalysis(any());
        verify(events).publishEvent(any(ArtistSearchRequested.class));
        assertThat(response.analysisId()).isEqualTo(31L);
    }

    @Test
    void delegatesArtistSearchCallbackAndReturnsParentAnalysisId() {
        VoiceAnalysisComponentJob artistJob = mock(VoiceAnalysisComponentJob.class);
        when(parentJobs.findByRequestIdForUpdate("artist-request")).thenReturn(Optional.empty());
        when(jobs.findByRequestIdForUpdate("artist-request")).thenReturn(Optional.of(artistJob));
        when(artistJob.getAttemptNo()).thenReturn(1);
        when(artistJob.getComponentType()).thenReturn("ARTIST_SEARCH");
        when(artistJob.getAnalysisJob()).thenReturn(parent);
        when(artistJob.getStatus()).thenReturn("COMPLETED");

        var request = new VoiceAnalysisComponentCallbackRequest(
                "artist-request", "artist-job-1", "ARTIST_SEARCH",
                VoiceAnalysisComponentCallbackRequest.Status.COMPLETED,
                "ARTIST_SEARCH", "fusion-v2", null, null, null, null,
                null, null, LocalDateTime.now(), 1,
                null, null, null, null, null, null, null,
                2L, "catalog-v1", java.util.List.of());

        var response = service.accept(request);

        verify(artistSearchCallbacks).accept(artistJob, request);
        assertThat(response.analysisId()).isEqualTo(31L);
        assertThat(response.status()).isEqualTo("COMPLETED");
    }

    @Test
    void acceptsARepeatedTerminalArtistSearchCallbackAsDuplicate() {
        VoiceAnalysisComponentJob artistJob = mock(VoiceAnalysisComponentJob.class);
        when(parentJobs.findByRequestIdForUpdate("artist-request")).thenReturn(Optional.empty());
        when(jobs.findByRequestIdForUpdate("artist-request")).thenReturn(Optional.of(artistJob));
        when(artistJob.getAttemptNo()).thenReturn(1);
        when(artistJob.getComponentType()).thenReturn("ARTIST_SEARCH");
        when(artistJob.getAnalysisJob()).thenReturn(parent);
        when(artistJob.getStatus()).thenReturn("COMPLETED");
        when(artistJob.isTerminal()).thenReturn(true);
        var request = new VoiceAnalysisComponentCallbackRequest(
                "artist-request", "artist-job-1", "ARTIST_SEARCH",
                VoiceAnalysisComponentCallbackRequest.Status.COMPLETED,
                "ARTIST_SEARCH", "fusion-v2", null, null, null, null,
                null, null, LocalDateTime.now(), 1,
                null, null, null, null, null, null, null,
                2L, "catalog-v1", java.util.List.of());

        var response = service.accept(request);

        assertThat(response.analysisId()).isEqualTo(31L);
        assertThat(response.duplicate()).isTrue();
        verify(artistSearchCallbacks, never()).accept(any(), any());
    }

    @Test
    void rejectsCompletedCallbackWithoutTheRequiredEmbedding() {
        assertThatThrownBy(() -> service.accept(completed(null, 1000)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("임베딩");
    }

    @Test
    void acceptsARepeatedTerminalTimbreCallbackAsDuplicate() {
        when(job.isTerminal()).thenReturn(true);

        var response = service.accept(completed(null, 1000));

        assertThat(response.analysisId()).isEqualTo(31L);
        assertThat(response.duplicate()).isTrue();
        verify(results, never()).save(any());
    }

    @Test
    void reportsTheMissingValidChunkCountContract() {
        var mapper = new ObjectMapper();
        var request = new VoiceAnalysisComponentCallbackRequest("request-1", "ai-job-1", "SINGER_BYOL",
                VoiceAnalysisComponentCallbackRequest.Status.COMPLETED, "SINGER_BYOL", "v2",
                1000, Collections.nCopies(1000, 0.125), mapper.createObjectNode(),
                mapper.createObjectNode().put("snr", 20), null, null, LocalDateTime.now(),
                1, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("validChunkCount");
    }

    @Test
    void keepsParentRunningUntilBothModelComponentsFinish() {
        VoiceAnalysisComponentJob ecapa = mock(VoiceAnalysisComponentJob.class);
        when(ecapa.getComponentType()).thenReturn("ECAPA");
        when(ecapa.getStatus()).thenReturn("QUEUED");
        when(ecapa.isTerminal()).thenReturn(false);
        when(jobs.findByAnalysisJobIdForUpdate(31L)).thenReturn(java.util.List.of(ecapa, job));

        service.accept(completed(Collections.nCopies(1000, 0.125), 1000));

        verify(parent).running(any());
        verify(session, never()).completeTimbreAnalysis(any());
    }

    @Test
    void rejectsZeroAndOverflowVectorsBeforeCompletingOrSaving() {
        for (double value : new double[]{0.0, Double.MAX_VALUE}) {
            assertThatThrownBy(() -> service.accept(completed(Collections.nCopies(1000, value), 1000)))
                    .isInstanceOf(BusinessException.class);
        }
        verify(results, never()).save(any());
        verify(session, never()).completeTimbreAnalysis(any());
    }

    @Test
    void newAttemptReplacesThePreviousModelResult() {
        VoiceModelResult previous = new VoiceModelResult(session, 3L, "SINGER_BYOL", "old", 1000,
                "[]", "old-sha", "{}", null);
        when(results.findBySessionIdAndModelKey(7L, "SINGER_BYOL")).thenReturn(Optional.of(previous));
        service.accept(completed(Collections.nCopies(1000, 0.125), 1000));
        verify(results).save(previous);
        assertThat(previous.getModelVersion()).isEqualTo("v2");
        assertThat(previous.getVectorJson()).contains("0.125");
    }

    private VoiceAnalysisComponentCallbackRequest completed(java.util.List<Double> embedding, int dimension) {
        ObjectMapper mapper = new ObjectMapper();
        return new VoiceAnalysisComponentCallbackRequest("request-1", "ai-job-1", "SINGER_BYOL",
                VoiceAnalysisComponentCallbackRequest.Status.COMPLETED, "SINGER_BYOL", "v2",
                dimension, embedding, mapper.createObjectNode().put("validChunkCount", 5),
                mapper.createObjectNode().put("snr", 20), null, null, LocalDateTime.now(),
                1, null, null, null, null, null, null, null, null, null, null);
    }
}
