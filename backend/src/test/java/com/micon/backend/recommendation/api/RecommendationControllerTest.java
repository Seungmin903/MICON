package com.micon.backend.recommendation.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.common.exception.GlobalExceptionHandler;
import com.micon.backend.recommendation.service.RecommendationNotFoundException;
import com.micon.backend.recommendation.service.RecommendationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

	@Mock
	private RecommendationService recommendationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new RecommendationController(recommendationService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void returnsLatestRecommendationForAuthenticatedUser() throws Exception {
		RecommendationResponse.SongSummary song = new RecommendationResponse.SongSummary(
			2L, 1, "밤편지", List.of("아이유"), "cover.jpg", 253_000L,
			-1, new BigDecimal("95.00"), "안정 음역에 잘 맞습니다."
		);
		RecommendationResponse.Detail response = new RecommendationResponse.Detail(
			41L, 21L, "OVERALL", "COMPLETED", LocalDateTime.of(2026, 9, 2, 10, 0),
			null, "v2", List.of(song)
		);
		when(recommendationService.getLatest(7L, "overall", "rank")).thenReturn(response);

		mockMvc.perform(get("/recommendations/latest").param("mode", "overall").principal(() -> "7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.recommendationId").value(41))
			.andExpect(jsonPath("$.data.songs[0].rank").value(1))
			.andExpect(jsonPath("$.data.songs[0].albumImageUrl").value("cover.jpg"))
			.andExpect(jsonPath("$.data.songs[0].reason").value("안정 음역에 잘 맞습니다."));
	}

	@Test
	void returnsRecommendationSongReasonAndScores() throws Exception {
		RecommendationResponse.SongDetail response = new RecommendationResponse.SongDetail(
			41L, 2L, 1, "밤편지", List.of("아이유"), null, 253_000L, -1,
			new BigDecimal("95.00"), new BigDecimal("98.00"), new BigDecimal("91.00"),
			new BigDecimal("85.00"), "안정 음역에 잘 맞습니다."
		);
		when(recommendationService.getSong(7L, 41L, 2L)).thenReturn(response);

		mockMvc.perform(get("/recommendations/41/songs/2").principal(() -> "7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.rangeFitScore").value(98.00))
			.andExpect(jsonPath("$.data.recommendedKey").value(-1));
	}

	@Test
	void returnsNotFoundWhenRecommendationDoesNotBelongToUser() throws Exception {
		when(recommendationService.get(7L, 999L, "rank")).thenThrow(new RecommendationNotFoundException(999L));

		mockMvc.perform(get("/recommendations/999").principal(() -> "7"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND"));
	}
}
