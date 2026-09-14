package com.micon.backend.songfit.repository;

import com.micon.backend.songfit.api.SongFitResponse.BurdenSection;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SongFitQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SongFitQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean songExists(Long songId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM song WHERE song_id = :songId",
			Map.of("songId", songId),
			Long.class
		);
		return count != null && count > 0;
	}

	public Optional<UserRangeRow> findUserRange(Long userId, Long analysisId) {
		String sql = """
			SELECT vas.status, vrm.full_low_midi AS stable_low_midi,
			       vrm.full_high_midi AS stable_high_midi
			FROM user_voice_profile uvp
			JOIN voice_analysis_session vas ON vas.session_id = uvp.session_id
			LEFT JOIN voice_range_measurement vrm ON vrm.session_id = vas.session_id
			WHERE uvp.profile_id = :analysisId AND uvp.user_id = :userId
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("analysisId", analysisId, "userId", userId),
			(resultSet, rowNumber) -> new UserRangeRow(
				resultSet.getString("status"),
				resultSet.getBigDecimal("stable_low_midi"),
				resultSet.getBigDecimal("stable_high_midi")
			)
		).stream().findFirst();
	}

	public Optional<SongRangeRow> findLatestSongRange(Long songId) {
		String sql = """
			SELECT analysis_id, lowest_midi, highest_midi
			FROM song_analysis
			WHERE song_id = :songId AND status = 'COMPLETED'
			ORDER BY analyzed_at DESC, analysis_id DESC
			LIMIT 1
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("songId", songId),
			(resultSet, rowNumber) -> new SongRangeRow(
				resultSet.getLong("analysis_id"),
				resultSet.getBigDecimal("lowest_midi"),
				resultSet.getBigDecimal("highest_midi")
			)
		).stream().findFirst();
	}

	public Optional<RecommendationRow> findRecommendation(Long userId, Long analysisId, Long songId) {
		String sql = """
			SELECT rs.recommended_key, rs.overall_score, rs.range_fit_score,
			       rs.timbre_fit_score, rs.rhythm_fit_score, rs.reason_text,
			       r.algorithm_version
			FROM recommendation r
			JOIN recommendation_song rs ON rs.recommendation_id = r.recommendation_id
			WHERE r.user_id = :userId
			  AND r.profile_id = :analysisId
			  AND r.status = 'COMPLETED'
			  AND rs.song_id = :songId
			ORDER BY r.generated_at DESC, r.recommendation_id DESC
			LIMIT 1
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("userId", userId, "analysisId", analysisId, "songId", songId),
			(resultSet, rowNumber) -> new RecommendationRow(
				getNullableInteger(resultSet, "recommended_key"),
				resultSet.getBigDecimal("overall_score"),
				resultSet.getBigDecimal("range_fit_score"),
				resultSet.getBigDecimal("timbre_fit_score"),
				resultSet.getBigDecimal("rhythm_fit_score"),
				resultSet.getString("reason_text"),
				resultSet.getString("algorithm_version")
			)
		).stream().findFirst();
	}

	public List<BurdenSection> findBurdenSections(Long songId, Long songAnalysisId) {
		String sql = """
			SELECT section_id, section_type, section_name, start_ms, end_ms,
			       difficulty_type, difficulty_score, description
			FROM song_section
			WHERE song_id = :songId
			  AND analysis_id = :songAnalysisId
			  AND difficulty_type <> 'NONE'
			ORDER BY sequence_no, section_id
			""";
		return jdbcTemplate.query(
			sql,
			Map.of("songId", songId, "songAnalysisId", songAnalysisId),
			(resultSet, rowNumber) -> new BurdenSection(
				resultSet.getLong("section_id"),
				resultSet.getString("section_type"),
				resultSet.getString("section_name"),
				resultSet.getInt("start_ms"),
				resultSet.getInt("end_ms"),
				resultSet.getString("difficulty_type"),
				resultSet.getBigDecimal("difficulty_score"),
				resultSet.getString("description")
			)
		);
	}

	private static Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
		int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	public record UserRangeRow(String status, BigDecimal stableLowMidi, BigDecimal stableHighMidi) {
	}

	public record SongRangeRow(Long analysisId, BigDecimal lowestMidi, BigDecimal highestMidi) {
	}

	public record RecommendationRow(
		Integer recommendedKey,
		BigDecimal overallScore,
		BigDecimal rangeFitScore,
		BigDecimal timbreFitScore,
		BigDecimal rhythmFitScore,
		String reasonText,
		String algorithmVersion
	) {
	}
}
