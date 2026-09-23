package com.micon.backend.domain.song.repository;

import com.micon.backend.domain.song.dto.SongResponse;
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
	private static final String GENRE_CONDITION = """
			AND (:genreId IS NULL OR EXISTS (
			    SELECT 1
			    FROM song_genre filtered_song_genre
			    WHERE filtered_song_genre.song_id = s.song_id
			      AND filtered_song_genre.genre_id = :genreId
			))
			""";
	private static final String ARTIST_CONDITION = """
			AND (:artistId IS NULL OR EXISTS (
			    SELECT 1 FROM song_singer filtered_song_singer
			    WHERE filtered_song_singer.song_id = s.song_id
			      AND filtered_song_singer.singer_id = :artistId
			))
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 SongQueryRepository 객체를 생성한다.
	public SongQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findPage 조건으로 데이터베이스를 조회한다.
	public List<SongResponse.Summary> findPage(String query, int size, long offset) {
		return findPage(query, size, offset, null);
	}

	/** 회원의 선호 장르와 일치하는 장르 수가 많은 곡을 먼저 조회한다. */
	public List<SongResponse.Summary> findPage(String query, int size, long offset, Long userId) {
		return findPage(query, size, offset, userId, null);
	}

	/** 장르 필터가 있으면 해당 장르에 연결된 곡만 반환한다. */
	public List<SongResponse.Summary> findPage(String query, int size, long offset, Long userId, Long genreId) {
		return findPage(query, size, offset, userId, genreId, null);
	}

	public List<SongResponse.Summary> findPage(String query, int size, long offset, Long userId, Long genreId, Long artistId) {
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms,
				       NULLIF(sm.display_image_url, '') AS image_url
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				""" + SEARCH_CONDITION + GENRE_CONDITION + ARTIST_CONDITION + """
				ORDER BY (
				    SELECT COUNT(*)
				    FROM song_genre preferred_song_genre
				    JOIN user_genre_preference preferred_genre
				      ON preferred_genre.genre_id = preferred_song_genre.genre_id
				    WHERE preferred_song_genre.song_id = s.song_id
				      AND preferred_genre.user_id = :userId
				) DESC,
				s.title, s.song_id
				LIMIT :size OFFSET :offset
				""";
		MapSqlParameterSource params = searchParameters(query)
				.addValue("userId", userId)
				.addValue("genreId", genreId)
				.addValue("artistId", artistId)
				.addValue("size", size)
				.addValue("offset", offset);
		List<SongRow> songs = jdbcTemplate.query(sql, params, SONG_ROW_MAPPER);
		Map<String, List<String>> artists = findArtists(songs.stream().map(SongRow::songId).toList());
		return songs.stream()
				.map(song -> new SongResponse.Summary(
					song.songId(),
					song.title(),
					artists.getOrDefault(song.songId(), List.of()),
					song.imageUrl(),
					null,
					song.durationMs()
				))
				.toList();
	}

	/**
	 * 빈 검색어·장르 미지정 카탈로그 전용 페이지. 추천 탭은 이 경로를 연속으로 호출하므로,
	 * 검색 조건과 곡마다 실행되던 선호 장르 상관 서브쿼리를 피한다.
	 */
	public List<SongResponse.Summary> findCatalogPage(int size, long offset, Long userId) {
		String sql;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("size", size)
				.addValue("offset", offset);
		if (userId == null) {
			sql = """
					SELECT s.song_id, s.title, s.duration_ms,
					       NULLIF(sm.display_image_url, '') AS image_url
					FROM song s
					LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
					ORDER BY s.title, s.song_id
					LIMIT :size OFFSET :offset
					""";
		} else {
			params.addValue("userId", userId);
			sql = """
					SELECT s.song_id, s.title, s.duration_ms,
					       NULLIF(sm.display_image_url, '') AS image_url
					FROM song s
					LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
					LEFT JOIN (
					    SELECT song_genre.song_id, COUNT(*) AS preferred_genre_count
					    FROM song_genre
					    JOIN user_genre_preference preference
					      ON preference.genre_id = song_genre.genre_id
					    WHERE preference.user_id = :userId
					    GROUP BY song_genre.song_id
					) preferred ON preferred.song_id = s.song_id
					ORDER BY COALESCE(preferred.preferred_genre_count, 0) DESC, s.title, s.song_id
					LIMIT :size OFFSET :offset
					""";
		}
		List<SongRow> songs = jdbcTemplate.query(sql, params, SONG_ROW_MAPPER);
		Map<String, List<String>> artists = findArtists(songs.stream().map(SongRow::songId).toList());
		return songs.stream()
				.map(song -> new SongResponse.Summary(
					song.songId(),
					song.title(),
					artists.getOrDefault(song.songId(), List.of()),
					song.imageUrl(),
					null,
					song.durationMs()
				))
				.toList();
	}

	/** Empty-query genre filter. Start at song_genre to avoid generic search predicates and per-song preference subqueries. */
	public List<SongResponse.Summary> findGenrePage(long genreId, int size, long offset, Long userId) {
		String sql;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("genreId", genreId).addValue("size", size).addValue("offset", offset);
		if (userId == null) {
			sql = """
					SELECT s.song_id, s.title, s.duration_ms, NULLIF(sm.display_image_url, '') AS image_url
					FROM song_genre filtered
					JOIN song s ON s.song_id = filtered.song_id
					LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
					WHERE filtered.genre_id = :genreId
					ORDER BY s.title, s.song_id
					LIMIT :size OFFSET :offset
					""";
		} else {
			params.addValue("userId", userId);
			sql = """
					SELECT s.song_id, s.title, s.duration_ms, NULLIF(sm.display_image_url, '') AS image_url
					FROM song_genre filtered
					JOIN song s ON s.song_id = filtered.song_id
					LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
					LEFT JOIN (
					    SELECT song_genre.song_id, COUNT(*) AS preferred_genre_count
					    FROM song_genre
					    JOIN user_genre_preference preference ON preference.genre_id = song_genre.genre_id
					    WHERE preference.user_id = :userId
					    GROUP BY song_genre.song_id
					) preferred ON preferred.song_id = s.song_id
					WHERE filtered.genre_id = :genreId
					ORDER BY COALESCE(preferred.preferred_genre_count, 0) DESC, s.title, s.song_id
					LIMIT :size OFFSET :offset
					""";
		}
		List<SongRow> songs = jdbcTemplate.query(sql, params, SONG_ROW_MAPPER);
		Map<String, List<String>> artists = findArtists(songs.stream().map(SongRow::songId).toList());
		return songs.stream().map(song -> new SongResponse.Summary(song.songId(), song.title(),
				artists.getOrDefault(song.songId(), List.of()), song.imageUrl(), null, song.durationMs())).toList();
	}

	// 기능: count 조건으로 데이터베이스를 조회한다.
	public long count(String query) {
		return count(query, null);
	}

	/** 장르 필터를 적용한 곡 수를 반환한다. */
	public long count(String query, Long genreId) {
		return count(query, genreId, null);
	}

	public long count(String query, Long genreId, Long artistId) {
		String sql = """
				SELECT COUNT(*)
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				""" + SEARCH_CONDITION + GENRE_CONDITION + ARTIST_CONDITION;
		Long count = jdbcTemplate.queryForObject(
			sql,
			searchParameters(query).addValue("genreId", genreId).addValue("artistId", artistId),
			Long.class
		);
		return count == null ? 0 : count;
	}

	/** 빈 검색어·장르 미지정 카탈로그의 전체 곡 수는 검색 조건용 조인 없이 song 테이블만 센다. */
	public long countCatalog() {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM song", new MapSqlParameterSource(), Long.class);
		return count == null ? 0 : count;
	}

	public long countGenre(long genreId) {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM song_genre WHERE genre_id = :genreId",
				new MapSqlParameterSource("genreId", genreId), Long.class);
		return count == null ? 0 : count;
	}

	// 기능: findDetail 조건으로 데이터베이스를 조회한다.
	public Optional<SongDetailRow> findDetail(String songId) {
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms,
				       NULLIF(sm.display_image_url, '') AS image_url,
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
		String sql = "SELECT song_id FROM song WHERE status = 'ACTIVE' AND song_id IN (:songIds)";
		return new HashSet<>(jdbcTemplate.queryForList(sql, Map.of("songIds", songIds), String.class));
	}

	/** 추천 결과처럼 이미 알고 있는 곡 ID들의 화면용 요약을 한 번에 조회한다. */
	public Map<String, SongResponse.Summary> findSummariesByIds(Set<String> songIds) {
		if (songIds.isEmpty()) {
			return Collections.emptyMap();
		}
		String sql = """
				SELECT s.song_id, s.title, s.duration_ms,
				       NULLIF(sm.display_image_url, '') AS image_url
				FROM song s
				LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
				WHERE s.song_id IN (:songIds) AND s.status = 'ACTIVE'
				""";
		List<SongRow> songs = jdbcTemplate.query(sql, Map.of("songIds", songIds), SONG_ROW_MAPPER);
		Map<String, List<String>> artists = findArtists(songs.stream().map(SongRow::songId).toList());
		Map<String, SongResponse.Summary> summaries = new LinkedHashMap<>();
		for (SongRow song : songs) {
			summaries.put(song.songId(), new SongResponse.Summary(song.songId(), song.title(),
					artists.getOrDefault(song.songId(), List.of()), song.imageUrl(), null, song.durationMs()));
		}
		return summaries;
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
		resultSet.getString("image_url"),
		getNullableLong(resultSet, "duration_ms")
	);

	private static final RowMapper<SongDetailRow> SONG_DETAIL_ROW_MAPPER = (resultSet, rowNumber) -> new SongDetailRow(
		resultSet.getString("song_id"),
		resultSet.getString("title"),
		getNullableLong(resultSet, "duration_ms"),
		resultSet.getString("image_url"),
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

	private record SongRow(String songId, String title, String imageUrl, Long durationMs) {
	}

	public record SongDetailRow(
		String songId,
		String title,
		Long durationMs,
		String imageUrl,
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
