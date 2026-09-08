package com.micon.backend.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.recommendation.repository.RecommendationQueryRepository.SongSort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RecommendationQueryRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private RecommendationQueryRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:recommendation-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		createTables();
		insertFixtures();
		repository = new RecommendationQueryRepository(new NamedParameterJdbcTemplate(dataSource));
	}

	@Test
	void findsLatestRecommendationForUserAndMode() {
		RecommendationQueryRepository.RecommendationRow latest = repository.findLatest(7L, "OVERALL")
			.orElseThrow();

		assertThat(latest.recommendationId()).isEqualTo(41L);
		assertThat(latest.status()).isEqualTo("COMPLETED");
		assertThat(repository.findLatest(8L, "OVERALL")).isEmpty();
	}

	@Test
	void returnsRecommendedSongsInRankOrderWithArtists() {
		List<RecommendationQueryRepository.RecommendedSongRow> songs = repository.findSongs(41L, SongSort.RANK);

		assertThat(songs).extracting(RecommendationQueryRepository.RecommendedSongRow::songId)
			.containsExactly(1L, 2L);
		assertThat(songs.getFirst().reason()).isEqualTo("고음 부담이 줄어듭니다.");
		assertThat(repository.findArtists(List.of(1L, 2L)))
			.containsEntry(1L, List.of("아이유"))
			.containsEntry(2L, List.of("검정치마"));
	}

	@Test
	void sortsRecommendedSongsByScoreWhenRequested() {
		List<RecommendationQueryRepository.RecommendedSongRow> songs = repository.findSongs(41L, SongSort.SCORE);

		assertThat(songs).extracting(RecommendationQueryRepository.RecommendedSongRow::songId)
			.containsExactly(2L, 1L);
	}

	@Test
	void limitsRecommendationListToTopSixSongs() {
		for (long songId = 3; songId <= 8; songId++) {
			jdbcTemplate.update("INSERT INTO SONG VALUES (?, ?, ?)", songId, "추천곡 " + songId, 200000L);
			jdbcTemplate.update(
				"INSERT INTO RECOMMENDATION_SONG VALUES (41, ?, ?, 0, ?, '추천 사유', 80, NULL, 80)",
				songId,
				songId,
				100 - songId
			);
		}

		List<RecommendationQueryRepository.RecommendedSongRow> songs = repository.findSongs(41L, SongSort.RANK);

		assertThat(songs).hasSize(6);
		assertThat(songs).extracting(RecommendationQueryRepository.RecommendedSongRow::rank)
			.containsExactly(1, 2, 3, 4, 5, 6);
	}

	@Test
	void recommendationLookupIsScopedToOwner() {
		assertThat(repository.findById(7L, 41L)).isPresent();
		assertThat(repository.findById(8L, 41L)).isEmpty();
		assertThat(repository.findSong(41L, 2L)).isPresent();
		assertThat(repository.findSong(41L, 999L)).isEmpty();
	}

	private void createTables() {
		jdbcTemplate.execute("""
			CREATE TABLE RECOMMENDATION (
			  recommendation_id BIGINT PRIMARY KEY, user_id BIGINT, voice_analysis_id BIGINT,
			  mode VARCHAR(20), status VARCHAR(20), generated_at TIMESTAMP,
			  expires_at TIMESTAMP, algorithm_version VARCHAR(50)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE RECOMMENDATION_SONG (
			  recommendation_id BIGINT, song_id BIGINT, rank_no INT, recommended_key SMALLINT,
			  overall_score DECIMAL(5,2), reason_text VARCHAR(1000), range_fit_score DECIMAL(5,2),
			  timbre_fit_score DECIMAL(5,2), rhythm_fit_score DECIMAL(5,2)
			)
			""");
		jdbcTemplate.execute("CREATE TABLE SONG (song_id BIGINT PRIMARY KEY, title VARCHAR(100), duration_ms BIGINT)");
		jdbcTemplate.execute("CREATE TABLE SONG_METADATA (song_id BIGINT PRIMARY KEY, display_image_url VARCHAR(2048))");
		jdbcTemplate.execute("CREATE TABLE SINGER (singer_id BIGINT PRIMARY KEY, name VARCHAR(100))");
		jdbcTemplate.execute("CREATE TABLE SONG_SINGER (singer_id BIGINT, song_id BIGINT, `order` INT)");
		jdbcTemplate.execute("CREATE TABLE VOICE_ANALYSIS (voice_analysis_id BIGINT, user_id BIGINT, status VARCHAR(20))");
	}

	private void insertFixtures() {
		jdbcTemplate.update("""
			INSERT INTO RECOMMENDATION VALUES
			(40, 7, 20, 'OVERALL', 'COMPLETED', TIMESTAMP '2026-09-01 10:00:00', NULL, 'v1'),
			(41, 7, 21, 'OVERALL', 'COMPLETED', TIMESTAMP '2026-09-02 10:00:00', NULL, 'v2'),
			(42, 7, 21, 'RANGE', 'EMPTY', TIMESTAMP '2026-09-03 10:00:00', NULL, 'v2')
			""");
		jdbcTemplate.update("INSERT INTO SONG VALUES (1, '좋은 날', 233000), (2, '밤편지', 253000)");
		jdbcTemplate.update("INSERT INTO SONG_METADATA VALUES (1, 'good-day.jpg'), (2, 'night-letter.jpg')");
		jdbcTemplate.update("INSERT INTO SINGER VALUES (10, '아이유'), (11, '검정치마')");
		jdbcTemplate.update("INSERT INTO SONG_SINGER VALUES (10, 1, 1), (11, 2, 1)");
		jdbcTemplate.update("INSERT INTO VOICE_ANALYSIS VALUES (21, 7, 'COMPLETED')");
		jdbcTemplate.update("""
			INSERT INTO RECOMMENDATION_SONG VALUES
			(41, 1, 1, -2, 88, '고음 부담이 줄어듭니다.', 90, NULL, 77),
			(41, 2, 2, 0, 95, '안정 음역에 잘 맞습니다.', 98, 91, 85)
			""");
	}
}
