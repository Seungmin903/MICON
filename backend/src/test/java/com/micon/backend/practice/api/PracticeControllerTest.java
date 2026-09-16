package com.micon.backend.practice.api;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.practice.service.PracticeHistoryService;
import com.micon.backend.practice.service.PracticeResultService;
import com.micon.backend.practice.service.PracticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PracticeControllerTest {

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
	void deletesOneOwnedPracticeReport() throws Exception {
		mockMvc.perform(delete("/practices/report/50").principal(() -> "7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data").doesNotExist());

		verify(practiceService).delete(7L, 50L);
	}
}
