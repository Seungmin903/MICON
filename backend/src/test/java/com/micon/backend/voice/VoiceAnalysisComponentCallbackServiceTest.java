package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.voice.api.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.voice.application.VoiceAnalysisComponentCallbackService;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJob;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJobRepository;
import com.micon.backend.voice.domain.VoiceAnalysisSession;
import com.micon.backend.voice.domain.VoiceModelResult;
import com.micon.backend.voice.domain.VoiceModelResultRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class VoiceAnalysisComponentCallbackServiceTest {
    private final VoiceAnalysisComponentJobRepository jobs = mock(VoiceAnalysisComponentJobRepository.class);
    private final VoiceModelResultRepository results = mock(VoiceModelResultRepository.class);
    private final VoiceAnalysisComponentJob job = mock(VoiceAnalysisComponentJob.class);
    private final VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
    private final VoiceAnalysisComponentCallbackService service =
            new VoiceAnalysisComponentCallbackService(jobs, results, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(jobs.findByRequestIdForUpdate("request-1")).thenReturn(Optional.of(job));
        when(job.getId()).thenReturn(11L);
        when(job.getSession()).thenReturn(session);
        when(job.getComponentType()).thenReturn("SINGER_BYOL");
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
        assertThat(response.analysisId()).isEqualTo(11L);
    }

    @Test
    void rejectsCompletedCallbackWithoutTheRequiredEmbedding() {
        assertThatThrownBy(() -> service.accept(completed(null, 1000)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("임베딩");
    }

    private VoiceAnalysisComponentCallbackRequest completed(java.util.List<Double> embedding, int dimension) {
        ObjectMapper mapper = new ObjectMapper();
        return new VoiceAnalysisComponentCallbackRequest("request-1", "ai-job-1",
                VoiceAnalysisComponentCallbackRequest.Status.COMPLETED, "SINGER_BYOL", "v2",
                dimension, embedding, mapper.createObjectNode().put("validChunkCount", 5),
                mapper.createObjectNode().put("snr", 20), null, null, LocalDateTime.now());
    }
}
