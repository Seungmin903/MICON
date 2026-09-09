package com.micon.backend.recommendation;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.application.RecommendationDispatchResult;
import com.micon.backend.recommendation.application.RecommendationDispatchService;
import com.micon.backend.recommendation.application.RecommendationInput;
import com.micon.backend.recommendation.application.RecommendationInputAssembler;
import com.micon.backend.recommendation.application.RecommendationLifecycleService;
import com.micon.backend.recommendation.client.RecommendationJobGateway;
import com.micon.backend.recommendation.domain.RecommendationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationDispatchServiceTest {
    @Mock RecommendationInputAssembler inputs;
    @Mock RecommendationLifecycleService lifecycle;
    @Mock RecommendationJobGateway gateway;

    RecommendationDispatchService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationDispatchService(inputs, lifecycle, gateway);
    }

    @Test
    void submitsTopFiftyWithModeAndStoresReturnedJobId() {
        RecommendationInput input = input();
        when(inputs.assemble(1L, 10L)).thenReturn(input);
        when(lifecycle.create(1L, 10L, RecommendationMode.RANGE)).thenReturn(20L);
        when(gateway.submit(org.mockito.ArgumentMatchers.any())).thenReturn(
                new RecommendationJobGateway.Result("recommend-job-1", "queued"));

        RecommendationDispatchResult result = service.dispatch(1L, 10L, RecommendationMode.RANGE);

        ArgumentCaptor<RecommendationJobGateway.Command> command =
                ArgumentCaptor.forClass(RecommendationJobGateway.Command.class);
        verify(gateway).submit(command.capture());
        assertThat(command.getValue().input()).isSameAs(input);
        assertThat(command.getValue().mode()).isEqualTo(RecommendationMode.RANGE);
        assertThat(command.getValue().topK()).isEqualTo(50);
        verify(lifecycle).assignJob(20L, "recommend-job-1");
        assertThat(result).isEqualTo(new RecommendationDispatchResult(20L, "recommend-job-1", "queued"));
    }

    @Test
    void recordsFailureWhenAiCannotBeReached() {
        when(inputs.assemble(1L, 10L)).thenReturn(input());
        when(lifecycle.create(1L, 10L, RecommendationMode.OVERALL)).thenReturn(20L);
        when(gateway.submit(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("connection timed out"));

        assertThatThrownBy(() -> service.dispatch(1L, 10L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RECOMMENDATION_DISPATCH_FAILED));
        verify(lifecycle).fail(20L, "connection timed out");
    }

    @Test
    void recordsFailureWhenAiReturnsBlankJobId() {
        when(inputs.assemble(1L, 10L)).thenReturn(input());
        when(lifecycle.create(1L, 10L, RecommendationMode.TIMBRE)).thenReturn(20L);
        when(gateway.submit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RecommendationJobGateway.Result(" ", "queued"));

        assertThatThrownBy(() -> service.dispatch(1L, 10L, RecommendationMode.TIMBRE))
                .isInstanceOf(BusinessException.class);
        verify(lifecycle).fail(20L, "AI response does not contain jobId");
    }

    private RecommendationInput input() {
        return new RecommendationInput(
                10L,
                30L,
                new RecommendationInput.VoiceProfile(
                        null, null, null, null, null, null, null, null,
                        "{\"ecapa\":[0.1]}", null, null, null, null, null),
                List.of("발라드", "록"),
                List.of("아이유"));
    }
}
