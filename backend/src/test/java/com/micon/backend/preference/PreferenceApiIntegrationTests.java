package com.micon.backend.preference;

import com.micon.backend.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PreferenceApiIntegrationTests {

	private static final String FIRST_SONG_ID = "00000000-0000-0000-0000-000000000401";
	private static final String SECOND_SONG_ID = "00000000-0000-0000-0000-000000000402";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JdbcTemplate jdbcTemplate;

	private User user;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS USER_SETTING (
			  user_id BIGINT PRIMARY KEY,
			  save_original_recording BOOLEAN NOT NULL DEFAULT FALSE,
			  preference_onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
			  count_in_enabled BOOLEAN NOT NULL DEFAULT TRUE,
			  count_in_seconds SMALLINT NOT NULL DEFAULT 3,
			  vibration_enabled BOOLEAN NOT NULL DEFAULT TRUE,
			  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
			  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
			)
			""");
		jdbcTemplate.update("UPDATE genre SET preference_order = NULL");
		user = userRepository.saveAndFlush(new User(
			"preferences@micon.test",
			passwordEncoder.encode("Password123!")
		));
		jdbcTemplate.update("""
			INSERT INTO GENRE(genre_id, name, normalized_name, category, preference_order)
			VALUES (201, '발라드', '발라드', 'KOREAN', 2),
			       (202, '록', '록', 'KOREAN', 1)
			""");
		jdbcTemplate.update("""
			INSERT INTO SINGER(singer_id, name, normalized_name, image_url)
			VALUES (301, '아이유', '아이유', 'https://cdn.example/iu.jpg'),
			       (302, 'DAY6', 'day6', 'https://cdn.example/day6.jpg'),
			       (303, '100%_밴드', '100%_밴드', NULL)
			""");
		jdbcTemplate.update("""
			INSERT INTO song(song_id, title, status)
			VALUES (?, '테스트 곡', 'ACTIVE'), (?, '테스트 곡 2', 'ACTIVE')
			""", FIRST_SONG_ID, SECOND_SONG_ID);
		jdbcTemplate.update("INSERT INTO song_singer(song_id, singer_id) VALUES (?, 301), (?, 302)", FIRST_SONG_ID, SECOND_SONG_ID);
	}

	@Test
	void settingCreatedBeforePreferenceDraftDoesNotCompleteOnboarding() throws Exception {
		jdbcTemplate.update("""
			INSERT INTO USER_SETTING(user_id, save_original_recording)
			VALUES (?, TRUE)
			""", user.getId());

		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.onboardingCompleted").value(false));

		mockMvc.perform(get("/users/me/preferences").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.onboardingCompleted").value(false));

		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.onboardingCompleted").value(true));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT save_original_recording FROM USER_SETTING WHERE user_id = ?",
			Boolean.class,
			user.getId()
		)).isTrue();
	}

	@Test
	void returnsCatalogsAndFreshOnboardingState() throws Exception {
		mockMvc.perform(get("/genres").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].genreId").value(202))
			.andExpect(jsonPath("$.data[0].name").value("록"));
		// 장르 목록은 비로그인(온보딩 게스트)도 같은 결과를 받는다
		mockMvc.perform(get("/genres"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].genreId").value(202));

		mockMvc.perform(get("/artists")
				.param("query", "day")
				.param("page", "0")
				.param("size", "10")
				.with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].artistId").value(302))
			.andExpect(jsonPath("$.data.content[0].imageUrl").value(org.hamcrest.Matchers.containsString("180628_Day6")))
			.andExpect(jsonPath("$.data.content[0].imageLicense").value("CC BY 3.0"))
			.andExpect(jsonPath("$.data.content[0].imageSourceUrl").value(org.hamcrest.Matchers.containsString("commons.wikimedia.org/wiki/File:180628_Day6.jpg")))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.totalPages").value(1));

		mockMvc.perform(get("/users/me/preferences").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.genreIds").isEmpty())
			.andExpect(jsonPath("$.data.artistIds").isEmpty())
			.andExpect(jsonPath("$.data.onboardingCompleted").value(false));
	}

	@Test
	void restoresDraftThenCompletesAndUpdatesPreferences() throws Exception {
		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.genreIds[0]").value(201))
			.andExpect(jsonPath("$.data.artistIds[0]").value(301))
			.andExpect(jsonPath("$.data.onboardingCompleted").value(false));

		save(user, "{\"genreIds\":[201,202],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.onboardingCompleted").value(true));

		// 완료된 이전 화면에서 늦게 도착한 임시 저장은 최종 선택을 덮어쓰지 않는다.
		save(user, "{\"genreIds\":[202],\"artistIds\":[302],\"onboardingCompleted\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.genreIds.length()").value(2))
			.andExpect(jsonPath("$.data.artistIds[0]").value(301));

		// 설정 화면의 명시적인 완료 저장은 기존 취향을 변경할 수 있다.
		save(user, "{\"genreIds\":[202],\"artistIds\":[302],\"onboardingCompleted\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.genreIds[0]").value(202))
			.andExpect(jsonPath("$.data.artistIds[0]").value(302))
			.andExpect(jsonPath("$.data.onboardingCompleted").value(true));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT save_original_recording FROM USER_SETTING WHERE user_id = ?",
			Boolean.class,
			user.getId()
		)).isFalse();
	}

	@Test
	void rejectsInvalidSelectionsWithoutChangingStoredPreferences() throws Exception {
		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isOk());

		for (String body : new String[] {
			"{}",
			"{\"genreIds\":[201,201],\"artistIds\":[301],\"onboardingCompleted\":true}",
			"{\"genreIds\":[999],\"artistIds\":[301],\"onboardingCompleted\":true}",
			"{\"genreIds\":[201],\"artistIds\":[],\"onboardingCompleted\":true}"
		}) {
			save(user, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		mockMvc.perform(get("/users/me/preferences").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.genreIds[0]").value(201))
			.andExpect(jsonPath("$.data.artistIds[0]").value(301))
			.andExpect(jsonPath("$.data.onboardingCompleted").value(true));
	}

	@Test
	void isolatesPreferencesByAuthenticatedAccountAndValidatesSearchParameters() throws Exception {
		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isOk());
		User another = userRepository.saveAndFlush(new User(
			"another-preferences@micon.test",
			passwordEncoder.encode("Password123!")
		));

		mockMvc.perform(get("/users/me/preferences").with(jwtFor(another)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.genreIds").isEmpty())
			.andExpect(jsonPath("$.data.onboardingCompleted").value(false));
		mockMvc.perform(get("/artists").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2));
		mockMvc.perform(get("/artists").param("query", "%").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(0));
		mockMvc.perform(get("/artists").param("size", "101").with(jwtFor(user)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(get("/artists"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void artistAliasesReturnExistingIdentityAndCreditAndNullImagesRemainSelectable() throws Exception {
		mockMvc.perform(get("/artists").param("query", "데이식스").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].artistId").value(302))
			.andExpect(jsonPath("$.data.content[0].name").value("데이식스"));
		mockMvc.perform(get("/artists").param("query", "100%_").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty())
			.andExpect(jsonPath("$.data.totalElements").value(0));
		save(user, "{\"genreIds\":[201],\"artistIds\":[303],\"onboardingCompleted\":true}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void removesArtistsWithoutActiveSongsFromChoicesButKeepsExistingPreferencesReadable() throws Exception {
		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isOk());
		jdbcTemplate.update("UPDATE song SET status='INACTIVE' WHERE song_id = ?",
			jdbcTemplate.queryForObject("SELECT song_id FROM song_singer WHERE singer_id=301", String.class));

		mockMvc.perform(get("/artists").param("query", "아이유").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty())
			.andExpect(jsonPath("$.data.totalElements").value(0));
		mockMvc.perform(get("/users/me/preferences").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.artistIds[0]").value(301))
			.andExpect(jsonPath("$.data.artists[0].artistId").value(301));
	}

	@Test
	void hiddenCatalogGenresCannotBeSelectedAndSavedArtistsKeepCredits() throws Exception {
		jdbcTemplate.update("INSERT INTO genre(genre_id,name,normalized_name,category) VALUES (203,'태교','hidden-test','ETC')");
		jdbcTemplate.update("UPDATE singer SET image_url=NULL WHERE singer_id=301");
		save(user, "{\"genreIds\":[203],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isBadRequest());
		save(user, "{\"genreIds\":[201],\"artistIds\":[301],\"onboardingCompleted\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.artists[0].imageSourceUrl").value("https://commons.wikimedia.org/wiki/File:IU_singer.jpg"))
			.andExpect(jsonPath("$.data.artists[0].imageLicense").value("CC BY 3.0"));
		mockMvc.perform(get("/users/me/preferences").with(jwtFor(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.artists[0].imageAttribution").isNotEmpty());
	}

	private org.springframework.test.web.servlet.ResultActions save(User owner, String body) throws Exception {
		return mockMvc.perform(put("/users/me/preferences")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.with(jwtFor(owner)));
	}

	private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User owner) {
		return jwt().jwt(token -> token
			.subject(owner.getId().toString())
			.claim("sid", "preference-test-session"));
	}
}
