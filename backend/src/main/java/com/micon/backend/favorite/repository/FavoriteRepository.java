package com.micon.backend.favorite.repository;

import com.micon.backend.song.api.SongResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FavoriteRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public FavoriteRepository(NamedParameterJdbcTemplate jdbcTemplate) {
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

	public void add(long userId, Long songId) {
		try {
			jdbcTemplate.update(
				"INSERT INTO favorite_song(user_id, song_id, prefer_key) VALUES (:userId, :songId, NULL)",
				Map.of("userId", userId, "songId", songId)
			);
		} catch (DuplicateKeyException ignored) {
			// Repeated PUT requests are intentionally idempotent.
		}
	}

	public void remove(long userId, Long songId) {
		jdbcTemplate.update(
			"DELETE FROM favorite_song WHERE user_id = :userId AND song_id = :songId",
			Map.of("userId", userId, "songId", songId)
		);
	}

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
				resultSet.getLong("song_id"),
				resultSet.getString("title"),
				resultSet.getString("display_image_url"),
				resultSet.getObject("duration_ms", Long.class)
			)
		);
		Map<Long, List<String>> artists = findArtists(rows.stream().map(FavoriteSongRow::songId).toList());
		return rows.stream()
			.map(row -> new SongResponse.Summary(
				row.songId(), row.title(), artists.getOrDefault(row.songId(), List.of()),
				row.imageUrl(), row.durationMs()
			))
			.toList();
	}

	private Map<Long, List<String>> findArtists(List<Long> songIds) {
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
		Map<Long, List<String>> artists = new LinkedHashMap<>();
		jdbcTemplate.query(sql, Map.of("songIds", songIds), resultSet -> {
			artists.computeIfAbsent(resultSet.getLong("song_id"), ignored -> new ArrayList<>())
				.add(resultSet.getString("name"));
		});
		return artists;
	}

	private record FavoriteSongRow(Long songId, String title, String imageUrl, Long durationMs) {
	}
}
