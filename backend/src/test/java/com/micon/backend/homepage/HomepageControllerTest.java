package com.micon.backend.homepage;

import com.micon.backend.domain.homepage.api.HomepageController;
import com.micon.backend.domain.homepage.dto.HomepageSongStatus;
import com.micon.backend.domain.homepage.dto.HomepageSongView;
import com.micon.backend.domain.homepage.repository.HomepageSongRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.micon.backend.domain.song.dto.PageResponse;
import com.micon.backend.domain.song.dto.SongResponse;
import com.micon.backend.domain.song.service.SongService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class HomepageControllerTest {

	@Mock
	private SongService songService;
	@Mock
	private HomepageSongRepository homepageSongRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new HomepageController(songService, homepageSongRepository)).build();
	}

	@Test
	void rendersSongsFromTheCatalogService() throws Exception {
		SongResponse.Summary song = new SongResponse.Summary(
			"1", "좋은 날", List.of("아이유"), "https://example.com/cover.jpg", 233_000L
		);
		when(songService.search("아이유", 1, 30))
			.thenReturn(PageResponse.of(List.of(song), 1, 30, 61));
		when(homepageSongRepository.findStatuses(List.of("1"))).thenReturn(Map.of(
			"1", new HomepageSongStatus("1", false, true, true, true, true, true, true)
		));
		when(homepageSongRepository.findGenres(List.of("1"))).thenReturn(Map.of(
			"1", List.of("발라드", "팝")
		));

		mockMvc.perform(get("/homepage/songs").param("query", "아이유").param("page", "1"))
			.andExpect(status().isOk())
			.andExpect(view().name("homepage/songs"))
			.andExpect(model().attribute("query", "아이유"))
			.andExpect(model().attribute("totalElements", 61L))
			.andExpect(model().attribute("hasPrevious", true))
			.andExpect(model().attribute("hasNext", true))
			.andExpect(model().attributeExists("songs"));

		verify(songService).search("아이유", 1, 30);
		verify(homepageSongRepository).findStatuses(List.of("1"));
		verify(homepageSongRepository).findGenres(List.of("1"));
	}

	@Test
	void formatsSongValuesForTheView() {
		HomepageSongView view = HomepageSongView.from(
			new SongResponse.Summary("1", "좋은 날", List.of("아이유", "피처링"), null, 233_000L),
			new HomepageSongStatus("1", false, true, true, true, true, true, true),
			List.of("발라드", "팝")
		);

		assertThat(view.artists()).isEqualTo("아이유, 피처링");
		assertThat(view.genres()).isEqualTo("발라드, 팝");
		assertThat(view.duration()).isEqualTo("3:53");
		assertThat(view.practiceReady()).isTrue();
		assertThat(view.storedPracticeReady()).isFalse();
	}
}
