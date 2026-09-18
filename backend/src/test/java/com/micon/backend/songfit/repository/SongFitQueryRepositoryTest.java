package com.micon.backend.songfit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

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
		repository = new SongFitQueryRepository(new NamedParameterJdbcTemplate(dataSource), new ObjectMapper());
	}

	@Test
	void readsFitInputsOnlyForTheAnalysisOwner() {
		assertThat(repository.songExists("10")).isTrue();
		assertThat(repository.findUserRange(7L, 20L)).get().satisfies(range -> {
			assertThat(range.sessionStatus()).isEqualTo("SAVED");
			assertThat(range.measurementStatus()).isEqualTo("AVAILABLE");
			assertThat(range.stableLowMidi()).isEqualByComparingTo("55");
			assertThat(range.stableHighMidi()).isEqualByComparingTo("75");
		});
		assertThat(repository.findUserRange(8L, 20L)).isEmpty();
	}

	@Test
	void readsLatestSongAnalysisRecommendationAndBurdenSections() {
		SongFitQueryRepository.SongRangeRow songRange = repository.findLatestSongRange("10").orElseThrow();
		assertThat(songRange.analysisId()).isEqualTo(31L);
		assertThat(songRange.lowestMidi()).isEqualByComparingTo("51");

		assertThat(repository.findRecommendation(7L, 20L, "10")).get().satisfies(recommendation -> {
			assertThat(recommendation.recommendedKey()).isEqualTo(-2);
			assertThat(recommendation.overallScore()).isEqualByComparingTo("0.88");
			assertThat(recommendation.rangeFitScore()).isEqualByComparingTo("0.90");
			assertThat(recommendation.timbreFitScore()).isEqualByComparingTo("0.77");
			assertThat(recommendation.rhythmFitScore()).isNull();
			assertThat(recommendation.algorithmVersion()).isEqualTo("fit-v1");
			assertThat(recommendation.timbreAxis()).isEqualTo("ECAPA");
			assertThat(recommendation.timbreLabel()).isEqualTo("음색이 가까운 가수");
		});

		assertThat(repository.findBurdenSections("10", 31L)).singleElement().satisfies(section -> {
			assertThat(section.sectionName()).isEqualTo("후렴");
			assertThat(section.difficultyType()).isEqualTo("HIGH_NOTE");
		});
	}

	@Test
	void distinguishesOriginalKeyFromMissingRecommendationAndIgnoresPreviousProfileVersion() {
		jdbcTemplate.update("UPDATE USER_VOICE_PROFILE SET updated_at = TIMESTAMP '2026-09-04 10:00:00' WHERE profile_id = 20");
		assertThat(repository.findRecommendation(7L, 20L, "10")).isEmpty();

		jdbcTemplate.update("""
			INSERT INTO RECOMMENDATION VALUES
			(41, 7, 20, 'COMPLETED', TIMESTAMP '2026-09-04 10:00:01', 'fit-v2', 19, 1)
			""");
		jdbcTemplate.update("""
			INSERT INTO RECOMMENDATION_SONG
			(recommendation_id, song_id, recommended_key, final_score, timbre_score, range_fit, metrics, reason_text)
			VALUES (41, '10', 0, 0.81, 0.73, '{"score":0.92,"keyShift":0}', '{}', '원키가 적합합니다.')
			""");

		assertThat(repository.findRecommendation(7L, 20L, "10")).get().satisfies(recommendation -> {
			assertThat(recommendation.recommendedKey()).isZero();
			assertThat(recommendation.rangeFitScore()).isEqualByComparingTo("0.92");
			assertThat(recommendation.timbreAxis()).isNull();
			assertThat(recommendation.timbreLabel()).isNull();
		});
	}

	private void createTables() {
		jdbcTemplate.execute("CREATE TABLE SONG (song_id VARCHAR(64) PRIMARY KEY, title VARCHAR(100))");
		jdbcTemplate.execute("""
			CREATE TABLE VOICE_ANALYSIS_SESSION (
			  session_id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(30)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE USER_VOICE_PROFILE (
			  profile_id BIGINT PRIMARY KEY, user_id BIGINT, session_id BIGINT, updated_at TIMESTAMP, profile_version BIGINT
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE VOICE_RANGE_MEASUREMENT (
			  range_measurement_id BIGINT PRIMARY KEY, session_id BIGINT,
			  measurement_status VARCHAR(30),
			  full_low_midi DECIMAL(6,2), full_high_midi DECIMAL(6,2)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_ANALYSIS (
			  analysis_id BIGINT PRIMARY KEY, song_id VARCHAR(64), status VARCHAR(20),
			  lowest_midi DECIMAL(6,2), highest_midi DECIMAL(6,2), analyzed_at TIMESTAMP
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE RECOMMENDATION (
			  recommendation_id BIGINT PRIMARY KEY, user_id BIGINT, profile_id BIGINT,
			  status VARCHAR(20), generated_at TIMESTAMP, algorithm_version VARCHAR(50), source_session_id BIGINT, profile_version BIGINT
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE RECOMMENDATION_SONG (
			  recommendation_id BIGINT, song_id VARCHAR(64), recommended_key SMALLINT,
			  final_score DECIMAL(8,6), timbre_score DECIMAL(8,6),
			  range_fit JSON, metrics JSON, reason_text VARCHAR(1000), axis VARCHAR(30), label VARCHAR(200)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE SONG_SECTION (
			  section_id BIGINT PRIMARY KEY, song_id VARCHAR(64), analysis_id BIGINT,
			  section_type VARCHAR(20), section_name VARCHAR(100), start_ms INT, end_ms INT,
			  sequence_no SMALLINT, difficulty_type VARCHAR(20), difficulty_score DECIMAL(5,2),
			  description VARCHAR(500)
			)
			""");
	}

	private void insertFixtures() {
		jdbcTemplate.update("INSERT INTO SONG VALUES (10, '좋은 날')");
		jdbcTemplate.update("INSERT INTO VOICE_ANALYSIS_SESSION VALUES (19, 7, 'SAVED')");
		jdbcTemplate.update("INSERT INTO USER_VOICE_PROFILE VALUES (20, 7, 19, TIMESTAMP '2026-09-02 10:00:00', 1)");
		jdbcTemplate.update("INSERT INTO VOICE_RANGE_MEASUREMENT VALUES (21, 19, 'AVAILABLE', 55, 75)");
		jdbcTemplate.update("""
			INSERT INTO SONG_ANALYSIS VALUES
			(30, 10, 'COMPLETED', 50, 70, TIMESTAMP '2026-09-01 10:00:00'),
			(31, 10, 'COMPLETED', 51, 71, TIMESTAMP '2026-09-02 10:00:00'),
			(32, 10, 'FAILED', 10, 20, TIMESTAMP '2026-09-03 10:00:00')
			""");
		jdbcTemplate.update("INSERT INTO RECOMMENDATION VALUES (40, 7, 20, 'COMPLETED', TIMESTAMP '2026-09-03 10:00:00', 'fit-v1', 19, 1)");
		jdbcTemplate.update("""
			INSERT INTO RECOMMENDATION_SONG
			(recommendation_id, song_id, recommended_key, final_score, timbre_score, range_fit, metrics, reason_text, axis, label)
			VALUES (40, '10', -2, 0.88, 0.77, '{"score":0.90,"keyShift":-2}', '{}', '음역이 잘 맞습니다.', 'ECAPA', '음색이 가까운 가수')
			""");
		jdbcTemplate.update("""
			INSERT INTO SONG_SECTION VALUES
			(50, 10, 31, 'VERSE', '1절', 0, 60000, 1, 'NONE', 10, NULL),
			(51, 10, 31, 'CHORUS', '후렴', 60000, 90000, 2, 'HIGH_NOTE', 82, '고음 구간')
			""");
	}
}
