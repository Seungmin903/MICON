package com.micon.backend.domain.favorite.repository;

import com.micon.backend.domain.song.dto.SongResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** favorite_song 테이블을 중심으로 회원의 찜 데이터와 곡 요약 정보를 조회·변경한다. */
@Repository
public class FavoriteRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 FavoriteRepository 객체를 생성한다.
	public FavoriteRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: songExists에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public boolean songExists(String songId) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM song WHERE song_id = :songId",
			Map.of("songId", songId),
			Long.class
		);
		return count != null && count > 0;
	}

	// 기능: add 데이터를 데이터베이스에 저장한다.
	public void add(long userId, String songId) {
		try {
			jdbcTemplate.update(
				"INSERT INTO favorite_song(user_id, song_id, prefer_key) VALUES (:userId, :songId, NULL)",
				Map.of("userId", userId, "songId", songId)
			);
		} catch (DuplicateKeyException ignored) {
			// Repeated PUT requests are intentionally idempotent.
		}
	}

	// 기능: remove 대상 데이터를 데이터베이스에서 삭제한다.
	public void remove(long userId, String songId) {
		jdbcTemplate.update(
			"DELETE FROM favorite_song WHERE user_id = :userId AND song_id = :songId",
			Map.of("userId", userId, "songId", songId)
		);
	}

	// 기능: findAll 조건으로 데이터베이스를 조회한다.
	public List<SongResponse.Summary> findAll(long userId) {
		String sql = """
			SELECT s.song_id, s.title, s.duration_ms, sm.display_image_url
			FROM favorite_song favorite
			JOIN song s ON s.song_id = favorite.song_id
			LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
			WHERE favorite.user_id = :userId
			ORDER BY s.title, s.song_id
			""";
		List<FavoriteSongRow> rows = jdbcTemplate.query(sql, Map.of("userId", userId), (resultSet, rowNumber) ->
			new FavoriteSongRow(
				resultSet.getString("song_id"),
				resultSet.getString("title"),
				resultSet.getString("display_image_url"),
				resultSet.getObject("duration_ms", Long.class)
			)
		);
		Map<String, List<String>> artists = findArtists(rows.stream().map(FavoriteSongRow::songId).toList());
		return rows.stream()
			.map(row -> new SongResponse.Summary(
				row.songId(), row.title(), artists.getOrDefault(row.songId(), List.of()),
				row.imageUrl(), row.durationMs()
			))
			.toList();
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
			ORDER BY ss.song_id, ss.display_order, si.singer_id
			""";
		Map<String, List<String>> artists = new LinkedHashMap<>();
		jdbcTemplate.query(sql, Map.of("songIds", songIds), resultSet -> {
			artists.computeIfAbsent(resultSet.getString("song_id"), ignored -> new ArrayList<>())
				.add(resultSet.getString("name"));
		});
		return artists;
	}

	private record FavoriteSongRow(String songId, String title, String imageUrl, Long durationMs) {
	}
}
