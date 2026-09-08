package com.micon.backend.song.repository;

import com.micon.backend.song.api.SongResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
public class SongQueryRepository {

	private static final String SEARCH_CONDITION = """
			WHERE (:query = ''
			   OR LOWER(COALESCE(s.title, '')) LIKE :queryPattern
			   OR LOWER(COALESCE(sm.tj_number, '')) LIKE :queryPattern
			   OR EXISTS (
			       SELECT 1
			       FROM SONG_SINGER search_ss
			       JOIN SINGER search_si ON search_si.singer_id = search_ss.singer_id
			       WHERE search_ss.song_id = s.song_id
			         AND LOWER(COALESCE(search_si.name, '')) LIKE :queryPattern
			   ))
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SongQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<SongResponse.Summary> findPage(String query, int size, long offset) {
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms, sm.display_image_url
				FROM SONG s
				LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
				""" + SEARCH_CONDITION + """
				ORDER BY s.title, s.song_id
				LIMIT :size OFFSET :offset
				""";
		MapSqlParameterSource params = searchParameters(query)
				.addValue("size", size)
				.addValue("offset", offset);
		List<SongRow> songs = jdbcTemplate.query(sql, params, SONG_ROW_MAPPER);
		Map<Long, List<String>> artists = findArtists(songs.stream().map(SongRow::songId).toList());
		return songs.stream()
				.map(song -> new SongResponse.Summary(
					song.songId(),
					song.title(),
					artists.getOrDefault(song.songId(), List.of()),
					song.imageUrl(),
					song.durationMs()
				))
				.toList();
	}

	public long count(String query) {
		String sql = """
				SELECT COUNT(*)
				FROM SONG s
				LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
				""" + SEARCH_CONDITION;
		Long count = jdbcTemplate.queryForObject(sql, searchParameters(query), Long.class);
		return count == null ? 0 : count;
	}

	public Optional<SongDetailRow> findDetail(Long songId) {
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms, s.practice_ready,
				       sm.album_name, sm.display_image_url, sm.release_date,
				       sa.key_name, sa.tempo_bpm, sa.lowest_midi, sa.highest_midi
				FROM SONG s
				LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
				LEFT JOIN SONG_ANALYSIS sa ON sa.analysis_id = (
				    SELECT latest.analysis_id
				    FROM SONG_ANALYSIS latest
				    WHERE latest.song_id = s.song_id AND latest.status = 'COMPLETED'
				    ORDER BY latest.analyzed_at DESC, latest.analysis_id DESC
				    LIMIT 1
				)
				WHERE s.song_id = :songId
				""";
		List<SongDetailRow> rows = jdbcTemplate.query(sql, Map.of("songId", songId), SONG_DETAIL_ROW_MAPPER);
		return rows.stream().findFirst();
	}

	public List<String> findArtists(Long songId) {
		return findArtists(List.of(songId)).getOrDefault(songId, List.of());
	}

	public List<String> findGenres(Long songId) {
		String sql = """
				SELECT g.name
				FROM SONG_GENRE sg
				JOIN GENRE g ON g.genre_id = sg.genre_id
				WHERE sg.song_id = :songId
				ORDER BY sg.is_primary DESC, g.name
				""";
		return jdbcTemplate.queryForList(sql, Map.of("songId", songId), String.class);
	}

	public boolean isFavorite(Long songId, Long userId) {
		if (userId == null) {
			return false;
		}
		String sql = """
				SELECT COUNT(*)
				FROM FAVORITE_SONG favorite
				WHERE favorite.song_id = :songId AND favorite.user_id = :userId
				""";
		Long count = jdbcTemplate.queryForObject(
			sql,
			Map.of("songId", songId, "userId", userId),
			Long.class
		);
		return count != null && count > 0;
	}

	private Map<Long, List<String>> findArtists(List<Long> songIds) {
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

	private MapSqlParameterSource searchParameters(String query) {
		String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
		return new MapSqlParameterSource()
				.addValue("query", normalizedQuery)
				.addValue("queryPattern", "%" + normalizedQuery + "%");
	}

	private static final RowMapper<SongRow> SONG_ROW_MAPPER = (resultSet, rowNumber) -> new SongRow(
		resultSet.getLong("song_id"),
		resultSet.getString("title"),
		getNullableLong(resultSet, "duration_ms"),
		resultSet.getString("display_image_url")
	);

	private static final RowMapper<SongDetailRow> SONG_DETAIL_ROW_MAPPER = (resultSet, rowNumber) -> new SongDetailRow(
		resultSet.getLong("song_id"),
		resultSet.getString("title"),
		getNullableLong(resultSet, "duration_ms"),
		getNullableBoolean(resultSet, "practice_ready"),
		resultSet.getString("album_name"),
		resultSet.getString("display_image_url"),
		resultSet.getObject("release_date", LocalDate.class),
		resultSet.getString("key_name"),
		resultSet.getBigDecimal("tempo_bpm"),
		resultSet.getBigDecimal("lowest_midi"),
		resultSet.getBigDecimal("highest_midi")
	);

	private static Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
		long value = resultSet.getLong(column);
		return resultSet.wasNull() ? null : value;
	}

	private static Boolean getNullableBoolean(ResultSet resultSet, String column) throws SQLException {
		boolean value = resultSet.getBoolean(column);
		return resultSet.wasNull() ? null : value;
	}

	private record SongRow(Long songId, String title, Long durationMs, String imageUrl) {
	}

	public record SongDetailRow(
		Long songId,
		String title,
		Long durationMs,
		Boolean practiceReady,
		String albumName,
		String imageUrl,
		LocalDate releaseDate,
		String originalKey,
		BigDecimal bpm,
		BigDecimal lowestMidi,
		BigDecimal highestMidi
	) {
	}
}
