package com.micon.backend.song.repository;

import com.micon.backend.song.api.SongResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC로 곡·가수·장르·메타데이터를 조회하며, 여러 테이블의 결과를 곡 조회 DTO로 조립한다. */
@Repository
public class SongQueryRepository {

	private static final String SEARCH_CONDITION = """
			WHERE (:query = ''
			   OR LOWER(COALESCE(s.title, '')) LIKE :queryPattern
			   OR LOWER(COALESCE(sm.tj_number, '')) LIKE :queryPattern
			   OR EXISTS (
			       SELECT 1
			       FROM song_singer search_ss
			       JOIN singer search_si ON search_si.singer_id = search_ss.singer_id
			       WHERE search_ss.song_id = s.song_id
			         AND LOWER(COALESCE(search_si.name, '')) LIKE :queryPattern
			   ))
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 SongQueryRepository 객체를 생성한다.
	public SongQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findPage 조건으로 데이터베이스를 조회한다.
	public List<SongResponse.Summary> findPage(String query, int size, long offset) {
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				""" + SEARCH_CONDITION + """
				ORDER BY s.title, s.song_id
				LIMIT :size OFFSET :offset
				""";
		MapSqlParameterSource params = searchParameters(query)
				.addValue("size", size)
				.addValue("offset", offset);
		List<SongRow> songs = jdbcTemplate.query(sql, params, SONG_ROW_MAPPER);
		Map<String, List<String>> artists = findArtists(songs.stream().map(SongRow::songId).toList());
		return songs.stream()
				.map(song -> new SongResponse.Summary(
					song.songId(),
					song.title(),
					artists.getOrDefault(song.songId(), List.of()),
					null,
					null,
					song.durationMs()
				))
				.toList();
	}

	// 기능: count 조건으로 데이터베이스를 조회한다.
	public long count(String query) {
		String sql = """
				SELECT COUNT(*)
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				""" + SEARCH_CONDITION;
		Long count = jdbcTemplate.queryForObject(sql, searchParameters(query), Long.class);
		return count == null ? 0 : count;
	}

	// 기능: findDetail 조건으로 데이터베이스를 조회한다.
	public Optional<SongDetailRow> findDetail(String songId) {
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms,
				       CASE
				           WHEN ready_analysis.analysis_id IS NOT NULL
				            AND sm.lyrics_synced IS NOT NULL
				            AND EXISTS (
				                SELECT 1
				                FROM song_note ready_note
				                WHERE ready_note.analysis_id = ready_analysis.analysis_id
				                  AND ready_note.karaoke_notes IS NOT NULL
				            )
				            AND EXISTS (
				                SELECT 1
				                FROM song_media ready_media
				                WHERE ready_media.song_id = s.song_id
				                  AND ready_media.generation_status = 'READY'
				                  AND ready_media.semitones = 0
				                  AND ready_media.kind IN ('INSTRUMENTAL', 'INSTRUMENTAL_KEY')
				            )
				           THEN TRUE ELSE FALSE
				       END AS practice_ready,
				       sm.album_name, sm.release_date,
				       sa.key_name, sa.tempo_bpm, sa.lowest_midi, sa.highest_midi
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				LEFT JOIN song_analysis sa ON sa.analysis_id = (
				    SELECT latest.analysis_id
				    FROM song_analysis latest
				    WHERE latest.song_id = s.song_id AND latest.status = 'COMPLETED'
				    ORDER BY latest.analyzed_at DESC, latest.analysis_id DESC
				    LIMIT 1
				)
				LEFT JOIN song_analysis ready_analysis ON ready_analysis.analysis_id = (
				    SELECT latest.analysis_id
				    FROM song_analysis latest
				    WHERE latest.song_id = s.song_id
				      AND latest.status IN ('COMPLETED', 'PARTIAL')
				    ORDER BY latest.analyzed_at DESC, latest.analysis_id DESC
				    LIMIT 1
				)
				WHERE s.song_id = :songId
				""";
		List<SongDetailRow> rows = jdbcTemplate.query(sql, Map.of("songId", songId), SONG_DETAIL_ROW_MAPPER);
		return rows.stream().findFirst();
	}

	// 기능: findExistingIds 조건으로 데이터베이스를 조회한다.
	public Set<String> findExistingIds(Set<String> songIds) {
		if (songIds.isEmpty()) {
			return Set.of();
		}
		String sql = "SELECT song_id FROM song WHERE song_id IN (:songIds)";
		return new HashSet<>(jdbcTemplate.queryForList(sql, Map.of("songIds", songIds), String.class));
	}

	// 기능: findArtists 조건으로 데이터베이스를 조회한다.
	public List<String> findArtists(String songId) {
		return findArtists(List.of(songId)).getOrDefault(songId, List.of());
	}

	// 기능: findGenres 조건으로 데이터베이스를 조회한다.
	public List<String> findGenres(String songId) {
		String sql = """
				SELECT g.name
				FROM song_genre sg
				JOIN genre g ON g.genre_id = sg.genre_id
				WHERE sg.song_id = :songId
				ORDER BY sg.is_primary DESC, g.name
				""";
		return jdbcTemplate.queryForList(sql, Map.of("songId", songId), String.class);
	}

	// 기능: isFavorite 조건으로 데이터베이스를 조회한다.
	public boolean isFavorite(String songId, Long userId) {
		if (userId == null) {
			return false;
		}
		String sql = """
				SELECT COUNT(*)
				FROM favorite_song favorite
				WHERE favorite.song_id = :songId AND favorite.user_id = :userId
				""";
		Long count = jdbcTemplate.queryForObject(
			sql,
			Map.of("songId", songId, "userId", userId),
			Long.class
		);
		return count != null && count > 0;
	}

	// 기능: findArtists 조건으로 데이터베이스를 조회한다.
	private Map<String, List<String>> findArtists(List<String> songIds) {
		if (songIds.isEmpty()) {
			return Collections.emptyMap();
		}
		String sql = """
				SELECT ss.song_id, si.name
				FROM song_singer ss
				JOIN singer si ON si.singer_id = ss.singer_id
				WHERE ss.song_id IN (:songIds)
				ORDER BY ss.song_id, ss.`display_order`, si.singer_id
				""";
		Map<String, List<String>> artists = new LinkedHashMap<>();
		jdbcTemplate.query(sql, Map.of("songIds", songIds), resultSet -> {
			artists.computeIfAbsent(resultSet.getString("song_id"), ignored -> new ArrayList<>())
					.add(resultSet.getString("name"));
		});
		return artists;
	}

	// 기능: searchParameters에 필요한 SQL 작업과 결과 매핑을 수행한다.
	private MapSqlParameterSource searchParameters(String query) {
		String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
		return new MapSqlParameterSource()
				.addValue("query", normalizedQuery)
				.addValue("queryPattern", "%" + normalizedQuery + "%");
	}

	private static final RowMapper<SongRow> SONG_ROW_MAPPER = (resultSet, rowNumber) -> new SongRow(
		resultSet.getString("song_id"),
		resultSet.getString("title"),
		getNullableLong(resultSet, "duration_ms")
	);

	private static final RowMapper<SongDetailRow> SONG_DETAIL_ROW_MAPPER = (resultSet, rowNumber) -> new SongDetailRow(
		resultSet.getString("song_id"),
		resultSet.getString("title"),
		getNullableLong(resultSet, "duration_ms"),
		getNullableBoolean(resultSet, "practice_ready"),
		resultSet.getString("album_name"),
		resultSet.getObject("release_date", LocalDate.class),
		resultSet.getString("key_name"),
		resultSet.getBigDecimal("tempo_bpm"),
		resultSet.getBigDecimal("lowest_midi"),
		resultSet.getBigDecimal("highest_midi")
	);

	// 기능: ResultSet의 정수 열에서 SQL NULL을 자바 null로 안전하게 변환한다.
	private static Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
		long value = resultSet.getLong(column);
		return resultSet.wasNull() ? null : value;
	}

	// 기능: ResultSet의 불리언 열에서 SQL NULL을 자바 null로 안전하게 변환한다.
	private static Boolean getNullableBoolean(ResultSet resultSet, String column) throws SQLException {
		boolean value = resultSet.getBoolean(column);
		return resultSet.wasNull() ? null : value;
	}

	private record SongRow(String songId, String title, Long durationMs) {
	}

	public record SongDetailRow(
		String songId,
		String title,
		Long durationMs,
		Boolean practiceReady,
		String albumName,
		LocalDate releaseDate,
		String originalKey,
		BigDecimal bpm,
		BigDecimal lowestMidi,
		BigDecimal highestMidi
	) {
	}
}
