package com.micon.backend.recommendation;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.application.RecommendationCreationService;
import com.micon.backend.recommendation.application.RecommendationDispatchResult;
import com.micon.backend.recommendation.application.RecommendationDispatchService;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysisStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationCreationServiceTest {

    private final VoiceAnalysisRepository analyses = mock(VoiceAnalysisRepository.class);
    private final RecommendationDispatchService dispatch = mock(RecommendationDispatchService.class);
    private final RecommendationCreationService service = new RecommendationCreationService(analyses, dispatch);

    @Test
    void createsRecommendationFromLatestCompletedAnalysis() {
        VoiceAnalysis analysis = mock(VoiceAnalysis.class);
        when(analysis.getId()).thenReturn(10L);
        when(analyses.findFirstByUserIdAndStatusOrderByCompletedAtDescIdDesc(1L, VoiceAnalysisStatus.COMPLETED))
                .thenReturn(Optional.of(analysis));
        RecommendationDispatchResult expected = new RecommendationDispatchResult(
                20L, "job-1", "queued", LocalDateTime.of(2026, 9, 9, 23, 0));
        when(dispatch.dispatch(1L, 10L, RecommendationMode.OVERALL)).thenReturn(expected);

        assertThat(service.createFromLatestAnalysis(1L)).isEqualTo(expected);
        verify(dispatch).dispatch(1L, 10L, RecommendationMode.OVERALL);
    }

    @Test
    void rejectsUserWithoutCompletedAnalysis() {
        when(analyses.findFirstByUserIdAndStatusOrderByCompletedAtDescIdDesc(1L, VoiceAnalysisStatus.COMPLETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createFromLatestAnalysis(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RECOMMENDATION_INPUT_NOT_READY));
    }
}
