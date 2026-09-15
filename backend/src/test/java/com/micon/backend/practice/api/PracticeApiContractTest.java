package com.micon.backend.practice.api;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.practice.service.PracticeHistoryService;
import com.micon.backend.practice.service.PracticeResultService;
import com.micon.backend.practice.service.PracticeService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PracticeApiContractTest {

	@Mock
	private PracticeService practiceService;
	@Mock
	private PracticeResultService resultService;
	@Mock
	private PracticeHistoryService historyService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(
			new PracticeController(practiceService, resultService, historyService)
		).build();
	}

	@Test
	void mapsPracticeHistoryContract() throws Exception {
		mockMvc.perform(get("/practices")
				.principal(() -> "7")
				.param("date", "2026-09-15")
				.param("page", "1")
				.param("size", "10"))
			.andExpect(status().isOk());

		verify(historyService).getHistory(7L, LocalDate.of(2026, 9, 15), 1, 10);
	}

	@Test
	void mapsPracticeSessionLifecycleContract() throws Exception {
		mockMvc.perform(post("/practices")
				.principal(() -> "7")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"songId": 3}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(patch("/practices/11/status")
				.principal(() -> "7")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"status": "PAUSED"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(post("/practices/11/complete")
				.principal(() -> "7")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"durationMs": 120000}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(post("/practices/11/cancel").principal(() -> "7"))
			.andExpect(status().isOk());
	}

	@Test
	void mapsPracticeReportContract() throws Exception {
		mockMvc.perform(put("/practices/11/performance")
				.principal(() -> "7")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"status": "FAILED"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(get("/practices/11/report").principal(() -> "7"))
			.andExpect(status().isOk());

		mockMvc.perform(post("/practices/11/analysis/retry").principal(() -> "7"))
			.andExpect(status().isOk());
	}
}
