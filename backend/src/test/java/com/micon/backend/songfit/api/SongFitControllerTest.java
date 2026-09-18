package com.micon.backend.songfit.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.common.exception.GlobalExceptionHandler;
import com.micon.backend.domain.songfit.api.SongFitController;
import com.micon.backend.domain.songfit.api.SongFitResponse;
import com.micon.backend.domain.songfit.service.SongFitService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SongFitControllerTest {

	@Mock
	private SongFitService songFitService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new SongFitController(songFitService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void usesAuthenticatedUserAndReturnsSelectedKeyFit() throws Exception {
		SongFitResponse.RangeFit fit = new SongFitResponse.RangeFit(
			new BigDecimal("55"), new BigDecimal("75"),
			new BigDecimal("55"), new BigDecimal("75"), new BigDecimal("100.00")
		);
		SongFitResponse response = new SongFitResponse(
			"10", 20L, 5, "AVAILABLE", fit, fit, -2,
			new SongFitResponse.RecommendationScores(new BigDecimal("0.88"), null, new BigDecimal("0.77"),
				null, "fit-v1", "ECAPA", "음색 설명"), null, List.of(), null
		);
		when(songFitService.getSongFit(7L, "10", 20L, 5)).thenReturn(response);

		mockMvc.perform(get("/songs/10/fit")
				.param("analysisId", "20")
				.param("keyOffset", "5")
				.principal(() -> "7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.analysisId").value(20))
			.andExpect(jsonPath("$.data.selectedKeyOffset").value(5))
			.andExpect(jsonPath("$.data.selectedKeyFit.score").value(100.00))
			.andExpect(jsonPath("$.data.recommendationScores.timbreAxis").value("ECAPA"))
			.andExpect(jsonPath("$.data.recommendationScores.timbreLabel").value("음색 설명"))
			.andExpect(jsonPath("$.data.recommendationScores.timbre").value(0.77));
	}
}
