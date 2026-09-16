package com.micon.backend.homepage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HomepageSongRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private HomepageSongRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:homepage-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", ""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		createTables();
		repository = new HomepageSongRepository(new NamedParameterJdbcTemplate(dataSource));
	}

	@Test
	void calculatesPracticeReadinessFromActualAssets() {
		jdbcTemplate.update("INSERT INTO song VALUES (1, FALSE), (2, TRUE)");
		jdbcTemplate.update("INSERT INTO song_metadata VALUES (1, '가사', '[00:01.00]가사')");
		jdbcTemplate.update("INSERT INTO song_analysis VALUES (10, 1, 'COMPLETED', TIMESTAMP '2026-09-16 10:00:00')");
		jdbcTemplate.update("INSERT INTO song_note VALUES (20, 10, '[{}]')");
		jdbcTemplate.update("INSERT INTO song_media VALUES (30, 1, 'INSTRUMENTAL', 0, 'READY')");

		Map<Long, HomepageSongStatus> statuses = repository.findStatuses(List.of(1L, 2L));

		assertThat(statuses.get(1L)).satisfies(status -> {
			assertThat(status.storedPracticeReady()).isFalse();
			assertThat(status.practiceReady()).isTrue();
			assertThat(status.lyricsPlainReady()).isTrue();
			assertThat(status.lyricsSyncedReady()).isTrue();
			assertThat(status.analysisReady()).isTrue();
			assertThat(status.targetNotesReady()).isTrue();
			assertThat(status.originalInstrumentalReady()).isTrue();
		});
		assertThat(statuses.get(2L).storedPracticeReady()).isTrue();
		assertThat(statuses.get(2L).practiceReady()).isFalse();
	}

	private void createTables() {
		jdbcTemplate.execute("CREATE TABLE song (song_id BIGINT PRIMARY KEY, practice_ready BOOLEAN)");
		jdbcTemplate.execute("CREATE TABLE song_metadata (song_id BIGINT PRIMARY KEY, lyrics_plain CLOB, lyrics_synced CLOB)");
		jdbcTemplate.execute("CREATE TABLE song_analysis (analysis_id BIGINT PRIMARY KEY, song_id BIGINT, status VARCHAR(20), analyzed_at TIMESTAMP)");
		jdbcTemplate.execute("CREATE TABLE song_note (song_note_id BIGINT PRIMARY KEY, analysis_id BIGINT, karaoke_notes CLOB)");
		jdbcTemplate.execute("CREATE TABLE song_media (media_id BIGINT PRIMARY KEY, song_id BIGINT, kind VARCHAR(30), semitones INT, generation_status VARCHAR(20))");
	}
}
