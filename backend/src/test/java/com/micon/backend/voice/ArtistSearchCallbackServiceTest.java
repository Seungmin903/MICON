package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.preference.api.PreferenceResponse;
import com.micon.backend.preference.repository.PreferenceCatalogRepository;
import com.micon.backend.voice.api.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.voice.application.ArtistSearchCallbackService;
import com.micon.backend.voice.domain.SimilarArtistResult;
import com.micon.backend.voice.domain.SimilarArtistResultRepository;
import com.micon.backend.voice.domain.VoiceAnalysisComponentJob;
import com.micon.backend.voice.domain.VoiceAnalysisSession;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ArtistSearchCallbackServiceTest {

    private final SimilarArtistResultRepository results = mock(SimilarArtistResultRepository.class);
    private final PreferenceCatalogRepository artists = mock(PreferenceCatalogRepository.class);
    private final VoiceAnalysisComponentJob job = mock(VoiceAnalysisComponentJob.class);
    private final VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
    private final ArtistSearchCallbackService service = new ArtistSearchCallbackService(results, artists);

    @BeforeEach
    void setUp() {
        when(job.getId()).thenReturn(51L);
        when(job.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(7L);
        when(session.getResultVersion()).thenReturn(2L);
        when(job.getRequestedResultVersion()).thenReturn(2L);
        when(job.getRequestedTopK()).thenReturn(10);
        when(job.getCatalogVersion()).thenReturn("catalog-v1");
        when(job.getScoringVersion()).thenReturn("fusion-v2");
        when(artists.findArtistsByIds(List.of(100L, 101L))).thenReturn(List.of(
                new PreferenceResponse.Artist(100L, "가수 100", null, null, null, null, null),
                new PreferenceResponse.Artist(101L, "가수 101", null, null, null, null, null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storesValidatedRankedArtistsAndComponentScores() {
        service.accept(job, completed(2L));

        ArgumentCaptor<List<SimilarArtistResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(results).deleteBySessionId(7L);
        verify(results).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().getFirst().getSingerId()).isEqualTo(100L);
        assertThat(captor.getValue().getFirst().getEcapaScore()).isEqualByComparingTo("0.89");
        assertThat(captor.getValue().getFirst().getSingerByolScore()).isEqualByComparingTo("0.93");
        verify(job).complete("ARTIST_SEARCH", "fusion-v2", completed(2L).processedAt());
        verify(session).completeArtistSearch(org.mockito.ArgumentMatchers.eq(false), any(LocalDateTime.class));
    }

    @Test
    void rejectsAStaleResultVersionWithoutReplacingResults() {
        assertThatThrownBy(() -> service.accept(job, completed(1L)))
                .isInstanceOf(BusinessException.class);
        verify(results, never()).deleteBySessionId(any());
        verify(results, never()).saveAll(any());
    }

    private VoiceAnalysisComponentCallbackRequest completed(long resultVersion) {
        LocalDateTime processedAt = LocalDateTime.of(2026, 9, 15, 10, 33);
        return new VoiceAnalysisComponentCallbackRequest(
                "0199f9f5-7e6a-7a20-bc91-23b8c1ed0a31", "artist-search-job-1",
                "ARTIST_SEARCH", VoiceAnalysisComponentCallbackRequest.Status.COMPLETED,
                "ARTIST_SEARCH", "fusion-v2", null, null, null, null,
                null, null, processedAt, 1, null, null, null, null, null, null, null,
                resultVersion, "catalog-v1", List.of(
                        new VoiceAnalysisComponentCallbackRequest.Artist(100L, 1, 0.91, 0.89, 0.93),
                        new VoiceAnalysisComponentCallbackRequest.Artist(101L, 2, 0.87, 0.88, 0.86)));
    }
}
