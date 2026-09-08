package com.micon.backend.practice.repository;

import com.micon.backend.practice.api.PracticeHistoryResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PracticeHistoryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public PracticeHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

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
				FROM PRACTICE_SESSION ps
				JOIN SONG s ON s.song_id = ps.song_id
				LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
				LEFT JOIN PRACTICE_RESULT pr ON pr.practice_result_id = (
				    SELECT latest.practice_result_id
				    FROM PRACTICE_RESULT latest
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
			resultSet.getLong("song_id"),
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

	public long count(Long userId, LocalDate date) {
		String sql = """
				SELECT COUNT(*)
				FROM PRACTICE_SESSION ps
				WHERE ps.user_id = :userId
				""" + dateCondition(date);
		Long count = jdbcTemplate.queryForObject(sql, parameters(userId, date), Long.class);
		return count == null ? 0 : count;
	}

	private String dateCondition(LocalDate date) {
		return date == null ? "" : " AND ps.created_at >= :fromAt AND ps.created_at < :toAt\n";
	}

	private MapSqlParameterSource parameters(Long userId, LocalDate date) {
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		if (date != null) {
			params.addValue("fromAt", date.atStartOfDay());
			params.addValue("toAt", date.plusDays(1).atStartOfDay());
		}
		return params;
	}
}
