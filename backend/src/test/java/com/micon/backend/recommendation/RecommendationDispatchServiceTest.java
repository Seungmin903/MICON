package com.micon.backend.recommendation;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchResult;
import com.micon.backend.domain.recommendation.service.RecommendationDispatchService;
import com.micon.backend.domain.recommendation.service.RecommendationInput;
import com.micon.backend.domain.recommendation.service.RecommendationInputAssembler;
import com.micon.backend.domain.recommendation.service.RecommendationLifecycleService;
import com.micon.backend.domain.recommendation.client.RecommendationJobGateway;
import com.micon.backend.domain.recommendation.client.RecommendationQueueFullException;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class RecommendationDispatchServiceTest {
    @Mock RecommendationInputAssembler inputs;
    @Mock RecommendationLifecycleService lifecycle;
    @Mock RecommendationJobGateway gateway;

    RecommendationDispatchService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationDispatchService(inputs, lifecycle, gateway, true);
    }

    @Test
    void rejectsDispatchBeforeCreatingStateWhenIntegrationIsDisabled() {
        service = new RecommendationDispatchService(inputs, lifecycle, gateway, false);

        assertThatThrownBy(() -> service.dispatch(1L, 10L, RecommendationMode.TIMBRE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RECOMMENDATION_DISPATCH_FAILED));
        org.mockito.Mockito.verifyNoInteractions(inputs, lifecycle, gateway);
    }

    @Test
    void submitsTopFiftyWithModeAndStoresReturnedJobId() {
        RecommendationInput input = input();
        when(inputs.assemble(1L, 10L)).thenReturn(input);
        LocalDateTime generatedAt = LocalDateTime.of(2026, 9, 9, 23, 0);
        when(lifecycle.create(1L, input, RecommendationMode.RANGE))
                .thenReturn(new RecommendationLifecycleService.Created(20L, generatedAt));
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
        assertThat(result).isEqualTo(
                new RecommendationDispatchResult(20L, "recommend-job-1", "queued", generatedAt));
    }

    @Test
    void recordsFailureWhenAiCannotBeReached() {
        when(inputs.assemble(1L, 10L)).thenReturn(input());
        when(lifecycle.create(1L, input(), RecommendationMode.OVERALL))
                .thenReturn(new RecommendationLifecycleService.Created(20L, LocalDateTime.now()));
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
        when(lifecycle.create(1L, input(), RecommendationMode.TIMBRE))
                .thenReturn(new RecommendationLifecycleService.Created(20L, LocalDateTime.now()));
        when(gateway.submit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RecommendationJobGateway.Result(" ", "queued"));

        assertThatThrownBy(() -> service.dispatch(1L, 10L, RecommendationMode.TIMBRE))
                .isInstanceOf(BusinessException.class);
        verify(lifecycle).fail(20L, "AI response does not contain jobId");
    }

    @Test
    void fullAiQueueLeavesRecommendationGeneratingForRetry() {
        RecommendationInput input = input();
        when(inputs.assemble(1L, 10L)).thenReturn(input);
        LocalDateTime generatedAt = LocalDateTime.now();
        when(lifecycle.create(1L, input, RecommendationMode.OVERALL))
                .thenReturn(new RecommendationLifecycleService.Created(20L, generatedAt));
        when(gateway.submit(any())).thenThrow(new RecommendationQueueFullException(new RuntimeException()));

        RecommendationDispatchResult result = service.dispatch(1L, 10L, RecommendationMode.OVERALL);

        assertThat(result.recommendationId()).isEqualTo(20L);
        assertThat(result.jobId()).isNull();
        verify(lifecycle).scheduleDispatchRetry(eq(20L), any(LocalDateTime.class));
        org.mockito.Mockito.verify(lifecycle, org.mockito.Mockito.never()).fail(eq(20L), anyString());
    }

    @Test
    void pendingDispatchUsesTheSameRecommendationIdWhenCapacityReturns() {
        RecommendationInput input = input();
        when(inputs.assemble(1L, 10L)).thenReturn(input);
        when(lifecycle.claimDispatchRetry(eq(20L), any(LocalDateTime.class),
                eq(Duration.ofSeconds(40)), eq(Duration.ofMinutes(2))))
                .thenReturn(Optional.of(new RecommendationLifecycleService.PendingDispatch(
                        20L, 1L, 10L, 20L, 1L, 0L, RecommendationMode.OVERALL, 1)));
        when(gateway.submit(any())).thenReturn(new RecommendationJobGateway.Result("retry-job", "queued"));

        service.retryPending(20L, Duration.ofSeconds(40), Duration.ofMinutes(2));

        verify(lifecycle).assignJob(20L, "retry-job");
    }

    @Test
    void fullQueueOnRetryKeepsTheSameRecommendationPending() {
        when(inputs.assemble(1L, 10L)).thenReturn(input());
        when(lifecycle.claimDispatchRetry(eq(20L), any(LocalDateTime.class),
                eq(Duration.ofSeconds(40)), eq(Duration.ofMinutes(2))))
                .thenReturn(Optional.of(new RecommendationLifecycleService.PendingDispatch(
                        20L, 1L, 10L, 20L, 1L, 0L, RecommendationMode.OVERALL, 1)));
        when(gateway.submit(any())).thenThrow(new RecommendationQueueFullException(new RuntimeException()));

        service.retryPending(20L, Duration.ofSeconds(40), Duration.ofMinutes(2));

        verify(lifecycle).scheduleDispatchRetry(eq(20L), any(LocalDateTime.class));
        org.mockito.Mockito.verify(lifecycle, org.mockito.Mockito.never()).fail(eq(20L), anyString());
    }

    private RecommendationInput input() {
        return new RecommendationInput(
                10L,
                20L, 1L,
                "30",
                new RecommendationInput.VoiceProfile(
                        null, null, null, null, null, null, null, null,
                        "{\"ecapa\":[0.1]}", null, null, null, null, null),
                List.of("발라드", "록"),
                List.of("아이유"));
    }
}
