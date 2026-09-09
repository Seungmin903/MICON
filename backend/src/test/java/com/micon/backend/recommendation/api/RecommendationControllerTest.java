package com.micon.backend.recommendation.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.recommendation.application.RecommendationCreationService;
import com.micon.backend.recommendation.application.RecommendationDispatchResult;
import com.micon.backend.recommendation.domain.RecommendationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationControllerTest {

    @Test
    void returnsAcceptedRecommendation() {
        RecommendationCreationService service = mock(RecommendationCreationService.class);
        LocalDateTime generatedAt = LocalDateTime.of(2026, 9, 9, 23, 0);
        when(service.createFromLatestAnalysis(1L))
                .thenReturn(new RecommendationDispatchResult(20L, "job-1", "queued", generatedAt));
        RecommendationController controller = new RecommendationController(service);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("1").build();

        ResponseEntity<ApiResponse<RecommendationCreateResponse>> response = controller.create(jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().recommendationId()).isEqualTo(20L);
        assertThat(response.getBody().data().jobId()).isEqualTo("job-1");
        assertThat(response.getBody().data().status()).isEqualTo(RecommendationStatus.GENERATING);
        assertThat(response.getBody().data().progress()).isZero();
        assertThat(response.getBody().data().generatedAt()).isEqualTo(generatedAt);
        assertThat(response.getBody().data().shelves()).isEmpty();
    }
}
