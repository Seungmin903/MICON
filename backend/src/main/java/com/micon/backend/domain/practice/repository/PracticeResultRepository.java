package com.micon.backend.domain.practice.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 연습 평가 결과와 구간별 평가를 저장하고 가장 최근 결과를 조회한다. */
@Repository
public class PracticeResultRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeResultRepository 객체를 생성한다.
	public PracticeResultRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findLatest 조건으로 데이터베이스를 조회한다.
	public Optional<ResultRow> findLatest(Long practiceId) {
		String sql = """
				SELECT practice_result_id, practice_id, status, overall_score, pitch_score,
				       sustain_score, rhythm_score, summary_text, analyzed_at, created_at
				FROM practice_result
				WHERE practice_id = :practiceId
				ORDER BY created_at DESC, practice_result_id DESC
				LIMIT 1
				""";
		return jdbcTemplate.query(sql, Map.of("practiceId", practiceId), (resultSet, rowNumber) -> new ResultRow(
			resultSet.getLong("practice_result_id"),
			resultSet.getLong("practice_id"),
			resultSet.getString("status"),
			resultSet.getBigDecimal("overall_score"),
			resultSet.getBigDecimal("pitch_score"),
			resultSet.getBigDecimal("sustain_score"),
			resultSet.getBigDecimal("rhythm_score"),
			resultSet.getString("summary_text"),
			resultSet.getObject("analyzed_at", LocalDateTime.class),
			resultSet.getObject("created_at", LocalDateTime.class)
		)).stream().findFirst();
	}

	// 기능: save 데이터를 데이터베이스에 저장한다.
	public void save(
		Long resultId,
		Long practiceId,
		String status,
		BigDecimal overallScore,
		BigDecimal pitchScore,
		BigDecimal sustainScore,
		BigDecimal rhythmScore,
		String summaryText
	) {
		Optional<ResultRow> existing = findLatest(practiceId);
		if (existing.isPresent()) {
			update(existing.get().resultId(), status, overallScore, pitchScore, sustainScore, rhythmScore, summaryText);
			return;
		}
		String sql = """
				INSERT INTO practice_result (
				    practice_result_id, practice_id, status, overall_score, pitch_score,
				    sustain_score, rhythm_score, summary_text, analyzed_at, created_at
				) VALUES (
				    :resultId, :practiceId, :status, :overallScore, :pitchScore,
				    :sustainScore, :rhythmScore, :summaryText, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
				)
				""";
		jdbcTemplate.update(sql, parameters(
			resultId, practiceId, status, overallScore, pitchScore, sustainScore, rhythmScore, summaryText
		));
	}

	// 기능: retry에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public int retry(Long resultId) {
		String sql = """
				UPDATE practice_result
				SET status = 'ANALYZING', overall_score = NULL, pitch_score = NULL,
				    sustain_score = NULL, rhythm_score = NULL, analyzed_at = NULL
				WHERE practice_result_id = :resultId AND status = 'FAILED'
				""";
		return jdbcTemplate.update(sql, Map.of("resultId", resultId));
	}

	// 기능: identifierExists에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public boolean identifierExists(Long resultId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM practice_result WHERE practice_result_id = :resultId",
			Map.of("resultId", resultId), Long.class);
		return count != null && count > 0;
	}

	// 기능: update 대상 데이터를 데이터베이스에서 변경한다.
	private void update(
		Long resultId,
		String status,
		BigDecimal overallScore,
		BigDecimal pitchScore,
		BigDecimal sustainScore,
		BigDecimal rhythmScore,
		String summaryText
	) {
		String sql = """
				UPDATE practice_result
				SET status = :status, overall_score = :overallScore, pitch_score = :pitchScore,
				    sustain_score = :sustainScore, rhythm_score = :rhythmScore,
				    summary_text = :summaryText, analyzed_at = CURRENT_TIMESTAMP(6)
				WHERE practice_result_id = :resultId
				""";
		jdbcTemplate.update(sql, parameters(
			resultId, null, status, overallScore, pitchScore, sustainScore, rhythmScore, summaryText
		));
	}

	// 기능: parameters에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	private MapSqlParameterSource parameters(
		Long resultId,
		Long practiceId,
		String status,
		BigDecimal overallScore,
		BigDecimal pitchScore,
		BigDecimal sustainScore,
		BigDecimal rhythmScore,
		String summaryText
	) {
		return new MapSqlParameterSource()
			.addValue("resultId", resultId)
			.addValue("practiceId", practiceId)
			.addValue("status", status)
			.addValue("overallScore", overallScore)
			.addValue("pitchScore", pitchScore)
			.addValue("sustainScore", sustainScore)
			.addValue("rhythmScore", rhythmScore)
			.addValue("summaryText", summaryText);
	}

	public record ResultRow(
		Long resultId,
		Long practiceId,
		String status,
		BigDecimal overallScore,
		BigDecimal pitchScore,
		BigDecimal sustainScore,
		BigDecimal rhythmScore,
		String summaryJson,
		LocalDateTime analyzedAt,
		LocalDateTime createdAt
	) {
	}
}
