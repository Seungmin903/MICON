package com.micon.backend.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.micon.backend.favorite.repository.FavoriteRepository;
import com.micon.backend.favorite.service.FavoriteService;
import com.micon.backend.song.service.SongNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FavoriteServiceTest {

	private JdbcTemplate jdbcTemplate;
	private FavoriteService service;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:favorite-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		jdbcTemplate.execute("CREATE TABLE SONG (song_id BIGINT PRIMARY KEY, title VARCHAR(100), duration_ms BIGINT)");
		jdbcTemplate.execute("CREATE TABLE SONG_METADATA (song_id BIGINT PRIMARY KEY, display_image_url VARCHAR(2048))");
		jdbcTemplate.execute("CREATE TABLE SINGER (singer_id BIGINT PRIMARY KEY, name VARCHAR(100))");
		jdbcTemplate.execute("CREATE TABLE SONG_SINGER (singer_id BIGINT, song_id BIGINT, `order` INT)");
		jdbcTemplate.execute("CREATE TABLE FAVORITE_SONG (user_id BIGINT, song_id BIGINT, prefer_key BIGINT, PRIMARY KEY(user_id, song_id))");
		jdbcTemplate.update("INSERT INTO SONG VALUES (1, '좋은 날', 233000)");
		jdbcTemplate.update("INSERT INTO SONG_METADATA VALUES (1, 'https://example.com/good-day.jpg')");
		jdbcTemplate.update("INSERT INTO SINGER VALUES (10, '아이유')");
		jdbcTemplate.update("INSERT INTO SONG_SINGER VALUES (10, 1, 1)");
		service = new FavoriteService(new FavoriteRepository(new NamedParameterJdbcTemplate(dataSource)));
	}

	@Test
	void addsListsAndRemovesFavoriteIdempotently() {
		service.add(7L, 1L);
		service.add(7L, 1L);

		var favorites = service.getFavorites(7L);
		assertThat(favorites.totalCount()).isEqualTo(1);
		assertThat(favorites.songs()).singleElement().satisfies(song -> {
			assertThat(song.songId()).isEqualTo(1L);
			assertThat(song.artists()).containsExactly("아이유");
		});
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM FAVORITE_SONG", Integer.class)).isEqualTo(1);

		service.remove(7L, 1L);
		service.remove(7L, 1L);
		assertThat(service.getFavorites(7L).songs()).isEmpty();
	}

	@Test
	void rejectsUnknownSong() {
		assertThatThrownBy(() -> service.add(7L, 999L))
			.isInstanceOf(SongNotFoundException.class);
	}
}
