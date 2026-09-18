package com.micon.backend.practice.repository;

import com.micon.backend.practice.api.PracticeHistoryResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 연습 세션과 최신 평가 결과를 결합하여 회원의 연습 기록 페이지를 조회한다. */
@Repository
public class PracticeHistoryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeHistoryRepository 객체를 생성한다.
	public PracticeHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findPage 조건으로 데이터베이스를 조회한다.
	public List<PracticeHistoryResponse.Summary> findPage(
		Long userId,
		LocalDate date,
		int size,
		long offset
	) {
		String sql = """
				SELECT ps.practice_id, ps.song_id, s.title AS song_title, sm.display_image_url,
				       ps.selected_key, ps.practice_scope, ps.section_id, ps.status,
				       ps.started_at, ps.duration_ms, pr.status AS result_status, pr.overall_score
				FROM practice_session ps
				JOIN song s ON s.song_id = ps.song_id
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				LEFT JOIN practice_result pr ON pr.practice_result_id = (
				    SELECT latest.practice_result_id
				    FROM practice_result latest
				    WHERE latest.practice_id = ps.practice_id
				    ORDER BY latest.created_at DESC, latest.practice_result_id DESC
				    LIMIT 1
				)
				WHERE ps.user_id = :userId
				""" + dateCondition(date) + """
				ORDER BY ps.created_at DESC, ps.practice_id DESC
				LIMIT :size OFFSET :offset
				""";
		MapSqlParameterSource params = parameters(userId, date)
			.addValue("size", size)
			.addValue("offset", offset);
		return jdbcTemplate.query(sql, params, (resultSet, rowNumber) -> new PracticeHistoryResponse.Summary(
			resultSet.getLong("practice_id"),
			resultSet.getString("song_id"),
			resultSet.getString("song_title"),
			resultSet.getString("display_image_url"),
			resultSet.getInt("selected_key"),
			resultSet.getString("practice_scope"),
			resultSet.getObject("section_id", Long.class),
			resultSet.getString("status"),
			resultSet.getObject("started_at", LocalDateTime.class),
			resultSet.getObject("duration_ms", Integer.class),
			resultSet.getString("result_status"),
			resultSet.getBigDecimal("overall_score")
		));
	}

	// 기능: count 조건으로 데이터베이스를 조회한다.
	public long count(Long userId, LocalDate date) {
		String sql = """
				SELECT COUNT(*)
				FROM practice_session ps
				WHERE ps.user_id = :userId
				""" + dateCondition(date);
		Long count = jdbcTemplate.queryForObject(sql, parameters(userId, date), Long.class);
		return count == null ? 0 : count;
	}

	// 기능: dateCondition에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	private String dateCondition(LocalDate date) {
		return date == null ? "" : " AND ps.created_at >= :fromAt AND ps.created_at < :toAt\n";
	}

	// 기능: parameters에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	private MapSqlParameterSource parameters(Long userId, LocalDate date) {
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		if (date != null) {
			params.addValue("fromAt", date.atStartOfDay());
			params.addValue("toAt", date.plusDays(1).atStartOfDay());
		}
		return params;
	}
}
