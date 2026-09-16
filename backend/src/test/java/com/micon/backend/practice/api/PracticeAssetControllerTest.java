package com.micon.backend.practice.api;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.practice.service.PracticeAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PracticeAssetControllerTest {

	@Mock
	private PracticeAssetService service;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new PracticeAssetController(service)).build();
	}

	@Test
	void mapsPracticeGuideWithSelectedKey() throws Exception {
		mockMvc.perform(get("/songs/3/practice-guide").param("selectedKey", "-2"))
			.andExpect(status().isOk());

		verify(service).getGuide("3", -2);
	}

	@Test
	void mapsMrWithSongIdAndSelectedKey() throws Exception {
		mockMvc.perform(get("/songs/3/mr").param("selectedKey", "-2"))
			.andExpect(status().isOk());

		verify(service).getMr("3", -2);
	}
}
