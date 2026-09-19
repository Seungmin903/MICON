package com.micon.backend.song.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.common.exception.GlobalExceptionHandler;
import com.micon.backend.domain.song.dto.PageResponse;
import com.micon.backend.domain.song.api.SongController;
import com.micon.backend.domain.song.dto.SongResponse;
import com.micon.backend.domain.song.service.SongNotFoundException;
import com.micon.backend.domain.song.service.SongService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SongControllerTest {

	@Mock
	private SongService songService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new SongController(songService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void returnsSongSearchResponseEnvelope() throws Exception {
		SongResponse.Summary song = new SongResponse.Summary(
			"1",
			"좋은 날",
			List.of("아이유"),
			"https://example.com/good-day.jpg",
			233_000L
		);
		when(songService.search("좋은", 0, 20, null)).thenReturn(PageResponse.of(List.of(song), 0, 20, 1));

		mockMvc.perform(get("/songs").param("query", "좋은"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.content[0].songId").value("1"))
				.andExpect(jsonPath("$.data.content[0].artists[0]").value("아이유"))
				.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	void usesJwtSubjectForPreferredGenreOrdering() throws Exception {
		when(songService.search("", 0, 20, 7L))
			.thenReturn(PageResponse.of(List.of(), 0, 20, 0));

		mockMvc.perform(get("/songs").principal(() -> "7"))
			.andExpect(status().isOk());

		org.mockito.Mockito.verify(songService).search("", 0, 20, 7L);
	}

	@Test
	void returnsNotFoundEnvelopeWhenSongDoesNotExist() throws Exception {
		when(songService.getDetail("999", null)).thenThrow(new SongNotFoundException("999"));

		mockMvc.perform(get("/songs/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void usesJwtSubjectAsUserIdForFavoriteLookup() throws Exception {
		SongResponse.Detail song = new SongResponse.Detail(
			"tj-00281", "좋은 날", List.of("아이유"), null, null, null, null, null, null,
			List.of(), null, null, true, true
		);
		when(songService.getDetail("tj-00281", 7L)).thenReturn(song);

		mockMvc.perform(get("/songs/tj-00281").principal(() -> "7"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.songId").value("tj-00281"))
				.andExpect(jsonPath("$.data.favorite").value(true));
	}
}
