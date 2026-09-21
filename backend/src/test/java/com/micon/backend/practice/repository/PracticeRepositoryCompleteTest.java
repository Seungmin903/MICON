package com.micon.backend.practice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.domain.practice.repository.PracticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PracticeRepositoryCompleteTest {

	private JdbcTemplate jdbcTemplate;
	private PracticeRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:practice-complete-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		jdbcTemplate.execute("""
			CREATE TABLE practice_session (
			  practice_id BIGINT PRIMARY KEY,
			  user_id BIGINT NOT NULL,
			  status VARCHAR(20) NOT NULL,
			  ended_at TIMESTAMP NULL,
			  duration_ms INT NULL,
			  recording_id BIGINT NULL,
			  initial_key SMALLINT NOT NULL DEFAULT 0,
			  selected_key SMALLINT NOT NULL DEFAULT 0,
			  key_change_count INT NOT NULL DEFAULT 0
			)
			""");
		repository = new PracticeRepository(new NamedParameterJdbcTemplate(dataSource));
	}

	@Test
	void completesReadyPractice() {
		insertPractice(10L, "READY");

		int updated = repository.complete(10L, 7L, 12_000, null, null, -2, 1);

		assertThat(updated).isEqualTo(1);
		assertThat(statusOf(10L)).isEqualTo("COMPLETED");
		assertThat(durationOf(10L)).isEqualTo(12_000);
		assertThat(selectedKeyOf(10L)).isEqualTo(-2);
		assertThat(keyChangeCountOf(10L)).isEqualTo(1);
	}

	@Test
	void doesNotCompleteTerminalPractice() {
		insertPractice(20L, "ABORTED");

		int updated = repository.complete(20L, 7L, 12_000, null, null, null, null);

		assertThat(updated).isZero();
		assertThat(statusOf(20L)).isEqualTo("ABORTED");
	}

	@Test
	void updatesOnlyWhenPracticeIsActiveAndCountsRealKeyChanges() {
		insertPractice(30L, "IN_PROGRESS");

		assertThat(repository.updateSelectedKey(30L, 7L, -2)).isEqualTo(1);
		assertThat(repository.updateSelectedKey(30L, 7L, -2)).isEqualTo(1);
		assertThat(repository.updateSelectedKey(30L, 7L, 1)).isEqualTo(1);

		assertThat(selectedKeyOf(30L)).isEqualTo(1);
		assertThat(keyChangeCountOf(30L)).isEqualTo(2);
	}

	@Test
	void doesNotUpdateKeyOfTerminalPractice() {
		insertPractice(40L, "COMPLETED");

		assertThat(repository.updateSelectedKey(40L, 7L, -2)).isZero();
		assertThat(selectedKeyOf(40L)).isZero();
		assertThat(keyChangeCountOf(40L)).isZero();
	}

	private void insertPractice(Long practiceId, String status) {
		jdbcTemplate.update(
			"INSERT INTO practice_session(practice_id, user_id, status) VALUES (?, ?, ?)",
			practiceId,
			7L,
			status
		);
	}

	private String statusOf(Long practiceId) {
		return jdbcTemplate.queryForObject(
			"SELECT status FROM practice_session WHERE practice_id = ?",
			String.class,
			practiceId
		);
	}

	private Integer durationOf(Long practiceId) {
		return jdbcTemplate.queryForObject(
			"SELECT duration_ms FROM practice_session WHERE practice_id = ?",
			Integer.class,
			practiceId
		);
	}

	private Integer selectedKeyOf(Long practiceId) {
		return jdbcTemplate.queryForObject(
			"SELECT selected_key FROM practice_session WHERE practice_id = ?",
			Integer.class,
			practiceId
		);
	}

	private Integer keyChangeCountOf(Long practiceId) {
		return jdbcTemplate.queryForObject(
			"SELECT key_change_count FROM practice_session WHERE practice_id = ?",
			Integer.class,
			practiceId
		);
	}
}
