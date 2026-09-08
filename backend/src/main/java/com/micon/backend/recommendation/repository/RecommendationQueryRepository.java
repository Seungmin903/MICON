package com.micon.backend.recommendation.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public RecommendationQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<RecommendationRow> findLatest(Long userId, String mode) {
		String sql = """
			SELECT recommendation_id, voice_analysis_id, mode, status,
			       generated_at, expires_at, algorithm_version
			FROM RECOMMENDATION
			WHERE user_id = :userId
			  AND (:mode IS NULL OR mode = :mode)
			ORDER BY generated_at DESC, recommendation_id DESC
			LIMIT 1
			""";
		return jdbcTemplate.query(
			sql,
			new MapSqlParameterSource().addValue("userId", userId).addValue("mode", mode),
			RECOMMENDATION_ROW_MAPPER
		).stream().findFirst();
	}

	public Optional<RecommendationRow> findById(Long userId, Long recommendationId) {
		String sql = """
			SELECT recommendation_id, voice_analysis_id, mode, status,
			       generated_at, expires_at, algorithm_version
			FROM RECOMMENDATION
			WHERE recommendation_id = :recommendationId AND user_id = :userId
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("recommendationId", recommendationId, "userId", userId),
			RECOMMENDATION_ROW_MAPPER
		).stream().findFirst();
	}

	public boolean hasCompletedVoiceAnalysis(Long userId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM VOICE_ANALYSIS WHERE user_id = :userId AND status = 'COMPLETED'",
			Map.of("userId", userId),
			Long.class
		);
		return count != null && count > 0;
	}

	public List<RecommendedSongRow> findSongs(Long recommendationId, SongSort sort) {
		String orderBy = sort == SongSort.SCORE
			? "ORDER BY rs.overall_score DESC, rs.rank_no, rs.song_id"
			: "ORDER BY rs.rank_no, rs.song_id";
		String sql = """
			SELECT rs.song_id, rs.rank_no, s.title, sm.display_image_url, s.duration_ms,
			       rs.recommended_key, rs.overall_score, rs.range_fit_score,
			       rs.timbre_fit_score, rs.rhythm_fit_score, rs.reason_text
			FROM RECOMMENDATION_SONG rs
			JOIN SONG s ON s.song_id = rs.song_id
			LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
			WHERE rs.recommendation_id = :recommendationId
			""" + orderBy;
		return jdbcTemplate.query(sql, Map.of("recommendationId", recommendationId), SONG_ROW_MAPPER);
	}

	public Optional<RecommendedSongRow> findSong(Long recommendationId, Long songId) {
		String sql = """
			SELECT rs.song_id, rs.rank_no, s.title, sm.display_image_url, s.duration_ms,
			       rs.recommended_key, rs.overall_score, rs.range_fit_score,
			       rs.timbre_fit_score, rs.rhythm_fit_score, rs.reason_text
			FROM RECOMMENDATION_SONG rs
			JOIN SONG s ON s.song_id = rs.song_id
			LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
			WHERE rs.recommendation_id = :recommendationId AND rs.song_id = :songId
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("recommendationId", recommendationId, "songId", songId),
			SONG_ROW_MAPPER
		).stream().findFirst();
	}

	public Map<Long, List<String>> findArtists(List<Long> songIds) {
		if (songIds.isEmpty()) {
			return Collections.emptyMap();
		}
		String sql = """
			SELECT ss.song_id, si.name
			FROM SONG_SINGER ss
			JOIN SINGER si ON si.singer_id = ss.singer_id
			WHERE ss.song_id IN (:songIds)
			ORDER BY ss.song_id, ss.`order`, si.singer_id
			""";
		Map<Long, List<String>> artists = new LinkedHashMap<>();
		jdbcTemplate.query(sql, Map.of("songIds", songIds), resultSet -> {
			artists.computeIfAbsent(resultSet.getLong("song_id"), ignored -> new ArrayList<>())
				.add(resultSet.getString("name"));
		});
		return artists;
	}

	private static final RowMapper<RecommendationRow> RECOMMENDATION_ROW_MAPPER = (resultSet, rowNumber) ->
		new RecommendationRow(
			resultSet.getLong("recommendation_id"),
			resultSet.getLong("voice_analysis_id"),
			resultSet.getString("mode"),
			resultSet.getString("status"),
			resultSet.getObject("generated_at", LocalDateTime.class),
			resultSet.getObject("expires_at", LocalDateTime.class),
			resultSet.getString("algorithm_version")
		);

	private static final RowMapper<RecommendedSongRow> SONG_ROW_MAPPER = (resultSet, rowNumber) ->
		new RecommendedSongRow(
			resultSet.getLong("song_id"),
			resultSet.getInt("rank_no"),
			resultSet.getString("title"),
			resultSet.getString("display_image_url"),
			getNullableLong(resultSet, "duration_ms"),
			getNullableInteger(resultSet, "recommended_key"),
			resultSet.getBigDecimal("overall_score"),
			resultSet.getBigDecimal("range_fit_score"),
			resultSet.getBigDecimal("timbre_fit_score"),
			resultSet.getBigDecimal("rhythm_fit_score"),
			resultSet.getString("reason_text")
		);

	private static Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
		int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	private static Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
		long value = resultSet.getLong(column);
		return resultSet.wasNull() ? null : value;
	}

	public record RecommendationRow(
		Long recommendationId,
		Long analysisId,
		String mode,
		String status,
		LocalDateTime generatedAt,
		LocalDateTime expiresAt,
		String algorithmVersion
	) {
	}

	public record RecommendedSongRow(
		Long songId,
		int rank,
		String title,
		String imageUrl,
		Long durationMs,
		Integer recommendedKey,
		BigDecimal overallScore,
		BigDecimal rangeFitScore,
		BigDecimal timbreFitScore,
		BigDecimal rhythmFitScore,
		String reason
	) {
	}

	public enum SongSort {
		RANK,
		SCORE
	}
}
