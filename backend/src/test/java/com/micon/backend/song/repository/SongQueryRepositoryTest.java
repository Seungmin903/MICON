package com.micon.backend.song.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.domain.song.dto.SongResponse;
import java.util.List;

import com.micon.backend.domain.song.repository.SongQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SongQueryRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private SongQueryRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:song-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		dropTables();
		createTables();
		insertFixtures();
		repository = new SongQueryRepository(new NamedParameterJdbcTemplate(dataSource));
	}

	@Test
	void searchesSongsByArtistAndReturnsPagedSummaries() {
		List<SongResponse.Summary> songs = repository.findPage("아이유", 20, 0);

		assertThat(repository.count("아이유")).isEqualTo(1);
		assertThat(songs).singleElement().satisfies(song -> {
			assertThat(song.songId()).isEqualTo("tj-00281");
			assertThat(song.title()).isEqualTo("좋은 날");
			assertThat(song.artists()).containsExactly("아이유");
			assertThat(song.imageUrl()).isEqualTo("https://example.com/good-day.jpg");
			assertThat(song.imageSourceUrl()).isNull();
			assertThat(song.durationMs()).isEqualTo(233_000L);
		});
	}

	@Test
	void returnsAllSongsOnTheRequestedPageWhenQueryIsBlank() {
		List<SongResponse.Summary> songs = repository.findPage("", 20, 0);

		assertThat(repository.count("")).isEqualTo(2);
		assertThat(songs).extracting(SongResponse.Summary::title)
			.containsExactly("밤편지", "좋은 날");
	}

	@Test
	void placesSongsMatchingTheUsersPreferredGenresFirst() {
		List<SongResponse.Summary> songs = repository.findPage("", 20, 0, 30L);

		assertThat(songs).extracting(SongResponse.Summary::title)
			.containsExactly("좋은 날", "밤편지");
	}

	@Test
	void keepsTheDefaultOrderWhenTheUserHasNoPreferredGenres() {
		List<SongResponse.Summary> songs = repository.findPage("", 20, 0, 31L);

		assertThat(songs).extracting(SongResponse.Summary::title)
			.containsExactly("밤편지", "좋은 날");
	}

	@Test
	void readsDetailFromProvidedSongTables() {
		SongQueryRepository.SongDetailRow detail = repository.findDetail("tj-00281").orElseThrow();

		assertThat(detail.originalKey()).isEqualTo("A♭ Major");
		assertThat(detail.bpm()).isEqualByComparingTo("128.500");
		assertThat(detail.practiceReady()).isTrue();
		assertThat(repository.findArtists("tj-00281")).containsExactly("아이유");
		assertThat(repository.findGenres("tj-00281")).containsExactly("K-POP", "발라드");
		assertThat(repository.isFavorite("tj-00281", 30L)).isTrue();
		assertThat(repository.isFavorite("tj-00999", 30L)).isFalse();
	}

	private void dropTables() {
		jdbcTemplate.execute("DROP ALL OBJECTS");
	}

	private void createTables() {
		jdbcTemplate.execute("""
			CREATE TABLE SONG (
			  song_id VARCHAR(64) PRIMARY KEY, title VARCHAR(100), duration_ms BIGINT,
			  practice_ready BOOLEAN
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_METADATA (
			  song_id VARCHAR(64) PRIMARY KEY, tj_number VARCHAR(20), album_name VARCHAR(255),
			  track_id VARCHAR(100), display_image_url VARCHAR(2048), image_type VARCHAR(30), source_url VARCHAR(2048),
			  release_date DATE, lyrics_synced CLOB
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SINGER (
			  singer_id BIGINT PRIMARY KEY, name VARCHAR(100)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_SINGER (
			  singer_id BIGINT, song_id VARCHAR(64), display_order INT
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE GENRE (
			  genre_id BIGINT PRIMARY KEY, name VARCHAR(100)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_GENRE (
			  song_id VARCHAR(64), genre_id BIGINT, is_primary BOOLEAN
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_ANALYSIS (
			  analysis_id BIGINT PRIMARY KEY, song_id VARCHAR(64), status VARCHAR(20),
			  key_name VARCHAR(30), tempo_bpm DECIMAL(7,3), lowest_midi DECIMAL(6,2),
			  highest_midi DECIMAL(6,2), analyzed_at TIMESTAMP
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_NOTE (
			  song_note_id BIGINT PRIMARY KEY, analysis_id BIGINT, karaoke_notes CLOB
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_MEDIA (
			  media_id BIGINT PRIMARY KEY, song_id VARCHAR(64), kind VARCHAR(30),
			  generation_status VARCHAR(20), semitones INT
			)
			""");
		jdbcTemplate.execute("CREATE TABLE FAVORITE_SONG (user_id BIGINT, song_id VARCHAR(64), prefer_key BIGINT)");
		jdbcTemplate.execute("CREATE TABLE USER_GENRE_PREFERENCE (user_id BIGINT, genre_id BIGINT)");
	}

	private void insertFixtures() {
		jdbcTemplate.update("INSERT INTO SONG VALUES ('tj-00281', '좋은 날', 233000, FALSE)");
		jdbcTemplate.update("INSERT INTO SONG VALUES ('tj-00999', '밤편지', 253000, FALSE)");
		jdbcTemplate.update("""
			INSERT INTO SONG_METADATA(song_id, tj_number, album_name, track_id, display_image_url, release_date, lyrics_synced)
			VALUES ('tj-00281', '33791', 'REAL', 'spotify-track-good-day', 'https://example.com/good-day.jpg', DATE '2010-12-09', '[00:01.00]첫 소절')
			""");
		jdbcTemplate.update("INSERT INTO SONG_METADATA(song_id, tj_number) VALUES ('tj-00999', '49818')");
		jdbcTemplate.update("INSERT INTO SINGER VALUES (10, '아이유')");
		jdbcTemplate.update("INSERT INTO SINGER VALUES (11, '검정치마')");
		jdbcTemplate.update("INSERT INTO SONG_SINGER VALUES (10, 'tj-00281', 1)");
		jdbcTemplate.update("INSERT INTO SONG_SINGER VALUES (11, 'tj-00999', 1)");
		jdbcTemplate.update("INSERT INTO GENRE VALUES (20, 'K-POP')");
		jdbcTemplate.update("INSERT INTO GENRE VALUES (21, '발라드')");
		jdbcTemplate.update("INSERT INTO SONG_GENRE VALUES ('tj-00281', 20, TRUE)");
		jdbcTemplate.update("INSERT INTO SONG_GENRE VALUES ('tj-00281', 21, FALSE)");
		jdbcTemplate.update("""
			INSERT INTO SONG_ANALYSIS VALUES
			(100, 'tj-00281', 'COMPLETED', 'A♭ Major', 128.500, 56.00, 81.00, TIMESTAMP '2026-09-01 10:00:00'),
			(101, 'tj-00281', 'FAILED', 'C Major', 100.000, 50.00, 70.00, TIMESTAMP '2026-09-02 10:00:00')
			""");
		jdbcTemplate.update("INSERT INTO SONG_NOTE VALUES (200, 100, '[{\"startMs\":1000}]')");
		jdbcTemplate.update("INSERT INTO SONG_MEDIA VALUES (300, 'tj-00281', 'INSTRUMENTAL', 'READY', 0)");
		jdbcTemplate.update("INSERT INTO FAVORITE_SONG VALUES (30, 'tj-00281', 0)");
		jdbcTemplate.update("INSERT INTO USER_GENRE_PREFERENCE VALUES (30, 21)");
	}
}
