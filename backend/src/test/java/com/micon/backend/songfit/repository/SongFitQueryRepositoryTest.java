package com.micon.backend.songfit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SongFitQueryRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private SongFitQueryRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:song-fit-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		createTables();
		insertFixtures();
		repository = new SongFitQueryRepository(new NamedParameterJdbcTemplate(dataSource));
	}

	@Test
	void readsFitInputsOnlyForTheAnalysisOwner() {
		assertThat(repository.songExists(10L)).isTrue();
		assertThat(repository.findUserRange(7L, 20L)).get().satisfies(range -> {
			assertThat(range.status()).isEqualTo("COMPLETED");
			assertThat(range.stableLowMidi()).isEqualByComparingTo("55");
			assertThat(range.stableHighMidi()).isEqualByComparingTo("75");
		});
		assertThat(repository.findUserRange(8L, 20L)).isEmpty();
	}

	@Test
	void readsLatestSongAnalysisRecommendationAndBurdenSections() {
		SongFitQueryRepository.SongRangeRow songRange = repository.findLatestSongRange(10L).orElseThrow();
		assertThat(songRange.analysisId()).isEqualTo(31L);
		assertThat(songRange.lowestMidi()).isEqualByComparingTo("51");

		assertThat(repository.findRecommendation(7L, 20L, 10L)).get().satisfies(recommendation -> {
			assertThat(recommendation.recommendedKey()).isEqualTo(-2);
			assertThat(recommendation.overallScore()).isEqualByComparingTo("88");
			assertThat(recommendation.algorithmVersion()).isEqualTo("fit-v1");
		});

		assertThat(repository.findBurdenSections(10L, 31L)).singleElement().satisfies(section -> {
			assertThat(section.sectionName()).isEqualTo("후렴");
			assertThat(section.difficultyType()).isEqualTo("HIGH_NOTE");
		});
	}

	private void createTables() {
		jdbcTemplate.execute("CREATE TABLE SONG (song_id BIGINT PRIMARY KEY, title VARCHAR(100))");
		jdbcTemplate.execute("""
			CREATE TABLE VOICE_ANALYSIS (
			  voice_analysis_id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(30)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE VOCAL_RANGE_ANALYSIS (
			  vocal_range_analysis_id BIGINT PRIMARY KEY, voice_analysis_id BIGINT,
			  stable_low_midi DECIMAL(6,2), stable_high_midi DECIMAL(6,2)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_ANALYSIS (
			  analysis_id BIGINT PRIMARY KEY, song_id BIGINT, status VARCHAR(20),
			  lowest_midi DECIMAL(6,2), highest_midi DECIMAL(6,2), analyzed_at TIMESTAMP
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE RECOMMENDATION (
			  recommendation_id BIGINT PRIMARY KEY, user_id BIGINT, voice_analysis_id BIGINT,
			  status VARCHAR(20), generated_at TIMESTAMP, algorithm_version VARCHAR(50)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE RECOMMENDATION_SONG (
			  recommendation_id BIGINT, song_id BIGINT, recommended_key SMALLINT,
			  overall_score DECIMAL(5,2), range_fit_score DECIMAL(5,2),
			  timbre_fit_score DECIMAL(5,2), rhythm_fit_score DECIMAL(5,2), reason_text VARCHAR(1000)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_SECTION (
			  section_id BIGINT PRIMARY KEY, song_id BIGINT, analysis_id BIGINT,
			  section_type VARCHAR(20), section_name VARCHAR(100), start_ms INT, end_ms INT,
			  sequence_no SMALLINT, difficulty_type VARCHAR(20), difficulty_score DECIMAL(5,2),
			  description VARCHAR(500)
			)
			""");
	}

	private void insertFixtures() {
		jdbcTemplate.update("INSERT INTO SONG VALUES (10, '좋은 날')");
		jdbcTemplate.update("INSERT INTO VOICE_ANALYSIS VALUES (20, 7, 'COMPLETED')");
		jdbcTemplate.update("INSERT INTO VOCAL_RANGE_ANALYSIS VALUES (21, 20, 55, 75)");
		jdbcTemplate.update("""
			INSERT INTO SONG_ANALYSIS VALUES
			(30, 10, 'COMPLETED', 50, 70, TIMESTAMP '2026-09-01 10:00:00'),
			(31, 10, 'COMPLETED', 51, 71, TIMESTAMP '2026-09-02 10:00:00'),
			(32, 10, 'FAILED', 10, 20, TIMESTAMP '2026-09-03 10:00:00')
			""");
		jdbcTemplate.update("INSERT INTO RECOMMENDATION VALUES (40, 7, 20, 'COMPLETED', TIMESTAMP '2026-09-03 10:00:00', 'fit-v1')");
		jdbcTemplate.update("INSERT INTO RECOMMENDATION_SONG VALUES (40, 10, -2, 88, 90, NULL, 77, '음역이 잘 맞습니다.')");
		jdbcTemplate.update("""
			INSERT INTO SONG_SECTION VALUES
			(50, 10, 31, 'VERSE', '1절', 0, 60000, 1, 'NONE', 10, NULL),
			(51, 10, 31, 'CHORUS', '후렴', 60000, 90000, 2, 'HIGH_NOTE', 82, '고음 구간')
			""");
	}
}
