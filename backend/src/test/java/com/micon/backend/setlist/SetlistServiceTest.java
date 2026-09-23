package com.micon.backend.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.setlist.repository.SetlistRepository;
import com.micon.backend.domain.setlist.exception.SetlistNotFoundException;
import com.micon.backend.domain.setlist.service.SetlistService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SetlistServiceTest {

	private JdbcTemplate jdbcTemplate;
	private SetlistService service;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:setlist-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		jdbcTemplate.execute("""
			CREATE TABLE SETLIST (
			  setlist_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
			  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
			  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SETLIST_ITEM (
			  setlist_item_id BIGINT PRIMARY KEY, setlist_id BIGINT NOT NULL, song_id VARCHAR(64) NOT NULL,
			  position_no INT NOT NULL, added_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
			  CONSTRAINT uk_setlist_item_position UNIQUE (setlist_id, position_no)
			)
			""");
		jdbcTemplate.execute("CREATE TABLE SONG (song_id VARCHAR(64) PRIMARY KEY, title VARCHAR(100), duration_ms BIGINT)");
		jdbcTemplate.execute("CREATE TABLE SONG_METADATA (song_id VARCHAR(64) PRIMARY KEY, display_image_url VARCHAR(2048))");
		jdbcTemplate.execute("CREATE TABLE SINGER (singer_id BIGINT PRIMARY KEY, name VARCHAR(100))");
		jdbcTemplate.execute("CREATE TABLE SONG_SINGER (singer_id BIGINT, song_id VARCHAR(64), display_order INT)");
		jdbcTemplate.execute("CREATE TABLE FAVORITE_SONG (user_id BIGINT, song_id VARCHAR(64), prefer_key BIGINT, PRIMARY KEY(user_id, song_id))");
		jdbcTemplate.update("INSERT INTO SONG VALUES (1, '좋은 날', 233000), (2, '밤편지', 253000)");
		jdbcTemplate.update("INSERT INTO SONG_METADATA VALUES (1, 'one.jpg'), (2, 'two.jpg')");
		jdbcTemplate.update("INSERT INTO SINGER VALUES (10, '아이유')");
		jdbcTemplate.update("INSERT INTO SONG_SINGER VALUES (10, 1, 1), (10, 2, 1)");
		jdbcTemplate.update("INSERT INTO FAVORITE_SONG VALUES (7, 1, NULL), (7, 2, NULL)");
		service = new SetlistService(new SetlistRepository(new NamedParameterJdbcTemplate(dataSource)));
	}

	@Test
	void managesOwnedSetlistAndKeepsFavorites() {
		var created = service.create(7L, "연습곡");
		assertThat(service.getSetlists(7L)).singleElement().satisfies(setlist -> {
			assertThat(setlist.name()).isEqualTo("연습곡");
			assertThat(setlist.songCount()).isZero();
		});

		service.addSong(7L, created.setlistId(), "1");
		var withSongs = service.addSong(7L, created.setlistId(), "2");
		service.addSong(7L, created.setlistId(), "2");
		assertThat(service.getSetlist(7L, created.setlistId()).songs()).hasSize(2);

		var reordered = service.reorderSongs(
			7L, created.setlistId(), List.of("2", "1"), withSongs.updatedAt()
		);
		assertThat(reordered.songs()).extracting(song -> song.songId()).containsExactly("2", "1");
		assertThat(reordered.songs()).extracting(song -> song.position()).containsExactly(1, 2);

		service.removeSong(7L, created.setlistId(), "2");
		assertThat(service.getSetlist(7L, created.setlistId()).songs()).singleElement()
			.extracting(song -> song.songId()).isEqualTo("1");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM FAVORITE_SONG WHERE user_id = 7", Integer.class
		)).isEqualTo(2);

		var renamed = service.rename(7L, created.setlistId(), "애창곡");
		assertThat(renamed.name()).isEqualTo("애창곡");
		service.delete(7L, created.setlistId());
		assertThat(service.getSetlists(7L)).isEmpty();
	}

	@Test
	void allowsNonFavoriteAndRejectsInvalidOrder() {
		var created = service.create(7L, "연습곡");
		jdbcTemplate.update("DELETE FROM FAVORITE_SONG WHERE user_id = 7 AND song_id = 2");

		service.addSong(7L, created.setlistId(), "2");
		service.addSong(7L, created.setlistId(), "1");
		assertThat(service.getSetlist(7L, created.setlistId()).songs()).hasSize(2);
		assertThatThrownBy(() -> service.reorderSongs(7L, created.setlistId(), List.of("1", "1"), null))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_SETLIST_ORDER));
	}

	@Test
	void hidesAnotherUsersSetlist() {
		var created = service.create(7L, "내 목록");

		assertThatThrownBy(() -> service.getSetlist(8L, created.setlistId()))
			.isInstanceOf(SetlistNotFoundException.class);
	}
}
