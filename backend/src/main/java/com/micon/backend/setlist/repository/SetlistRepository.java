package com.micon.backend.setlist.repository;

import com.micon.backend.setlist.api.SetlistResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SetlistRepository {

	private static final int ID_INSERT_ATTEMPTS = 3;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SetlistRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public SetlistRow create(long userId, String name) {
		for (int attempt = 0; attempt < ID_INSERT_ATTEMPTS; attempt++) {
			long setlistId = nextId();
			try {
				jdbcTemplate.update(
					"INSERT INTO SETLIST(setlist_id, user_id, name) VALUES (:setlistId, :userId, :name)",
					Map.of("setlistId", setlistId, "userId", userId, "name", name)
				);
				return findOwned(setlistId, userId).orElseThrow();
			} catch (DuplicateKeyException ignored) {
				// The supplied schema has no auto increment for this key; retry a random positive BIGINT.
			}
		}
		throw new IllegalStateException("세트리스트 식별자를 생성하지 못했습니다.");
	}

	public List<SetlistSummaryRow> findAll(long userId) {
		String sql = """
			SELECT sl.setlist_id, sl.name, sl.created_at, sl.updated_at, COUNT(item.setlist_item_id) AS song_count
			FROM SETLIST sl
			LEFT JOIN SETLIST_ITEM item ON item.setlist_id = sl.setlist_id
			WHERE sl.user_id = :userId
			GROUP BY sl.setlist_id, sl.name, sl.created_at, sl.updated_at
			ORDER BY sl.updated_at DESC, sl.setlist_id DESC
			""";
		return jdbcTemplate.query(sql, Map.of("userId", userId), (resultSet, rowNumber) ->
			new SetlistSummaryRow(
				resultSet.getLong("setlist_id"),
				resultSet.getString("name"),
				resultSet.getInt("song_count"),
				resultSet.getObject("created_at", LocalDateTime.class),
				resultSet.getObject("updated_at", LocalDateTime.class)
			)
		);
	}

	public Optional<SetlistRow> findOwned(Long setlistId, long userId) {
		return findOwned(setlistId, userId, false);
	}

	public Optional<SetlistRow> findOwnedForUpdate(Long setlistId, long userId) {
		return findOwned(setlistId, userId, true);
	}

	private Optional<SetlistRow> findOwned(Long setlistId, long userId, boolean forUpdate) {
		String sql = """
			SELECT setlist_id, name, created_at, updated_at
			FROM SETLIST
			WHERE setlist_id = :setlistId AND user_id = :userId
			""" + (forUpdate ? " FOR UPDATE" : "");
		List<SetlistRow> rows = jdbcTemplate.query(
			sql,
			Map.of("setlistId", setlistId, "userId", userId),
			(resultSet, rowNumber) -> new SetlistRow(
				resultSet.getLong("setlist_id"),
				resultSet.getString("name"),
				resultSet.getObject("created_at", LocalDateTime.class),
				resultSet.getObject("updated_at", LocalDateTime.class)
			)
		);
		return rows.stream().findFirst();
	}

	public SetlistRow rename(Long setlistId, long userId, String name) {
		jdbcTemplate.update(
			"UPDATE SETLIST SET name = :name, updated_at = CURRENT_TIMESTAMP(6) "
				+ "WHERE setlist_id = :setlistId AND user_id = :userId",
			Map.of("name", name, "setlistId", setlistId, "userId", userId)
		);
		return findOwned(setlistId, userId).orElseThrow();
	}

	public void delete(Long setlistId, long userId) {
		jdbcTemplate.update(
			"DELETE FROM SETLIST_ITEM WHERE setlist_id = :setlistId",
			Map.of("setlistId", setlistId)
		);
		jdbcTemplate.update(
			"DELETE FROM SETLIST WHERE setlist_id = :setlistId AND user_id = :userId",
			Map.of("setlistId", setlistId, "userId", userId)
		);
	}

	public boolean songExists(Long songId) {
		return count("SELECT COUNT(*) FROM SONG WHERE song_id = :songId", Map.of("songId", songId)) > 0;
	}

	public boolean isFavorite(long userId, Long songId) {
		return count(
			"SELECT COUNT(*) FROM FAVORITE_SONG WHERE user_id = :userId AND song_id = :songId",
			Map.of("userId", userId, "songId", songId)
		) > 0;
	}

	public boolean containsSong(Long setlistId, Long songId) {
		return count(
			"SELECT COUNT(*) FROM SETLIST_ITEM WHERE setlist_id = :setlistId AND song_id = :songId",
			Map.of("setlistId", setlistId, "songId", songId)
		) > 0;
	}

	public void addSong(Long setlistId, Long songId) {
		if (containsSong(setlistId, songId)) {
			return;
		}
		Integer nextPosition = jdbcTemplate.queryForObject(
			"SELECT COALESCE(MAX(position_no), 0) + 1 FROM SETLIST_ITEM WHERE setlist_id = :setlistId",
			Map.of("setlistId", setlistId),
			Integer.class
		);
		for (int attempt = 0; attempt < ID_INSERT_ATTEMPTS; attempt++) {
			try {
				jdbcTemplate.update(
					"INSERT INTO SETLIST_ITEM(setlist_item_id, setlist_id, song_id, position_no) "
						+ "VALUES (:itemId, :setlistId, :songId, :position)",
					Map.of(
						"itemId", nextId(), "setlistId", setlistId, "songId", songId,
						"position", nextPosition == null ? 1 : nextPosition
					)
				);
				touch(setlistId);
				return;
			} catch (DuplicateKeyException ignored) {
				// Retry only the generated item key.
			}
		}
		throw new IllegalStateException("세트리스트 항목 식별자를 생성하지 못했습니다.");
	}

	public void removeSong(Long setlistId, Long songId) {
		int removed = jdbcTemplate.update(
			"DELETE FROM SETLIST_ITEM WHERE setlist_id = :setlistId AND song_id = :songId",
			Map.of("setlistId", setlistId, "songId", songId)
		);
		if (removed > 0) {
			normalizePositions(setlistId);
			touch(setlistId);
		}
	}

	public void reorderSongs(Long setlistId, List<Long> songIds) {
		for (int index = 0; index < songIds.size(); index++) {
			jdbcTemplate.update(
				"UPDATE SETLIST_ITEM SET position_no = :position "
					+ "WHERE setlist_id = :setlistId AND song_id = :songId",
				Map.of("position", index + 1, "setlistId", setlistId, "songId", songIds.get(index))
			);
		}
		touch(setlistId);
	}

	public List<Long> findSongIds(Long setlistId) {
		return jdbcTemplate.queryForList(
			"SELECT song_id FROM SETLIST_ITEM WHERE setlist_id = :setlistId ORDER BY position_no, setlist_item_id",
			Map.of("setlistId", setlistId),
			Long.class
		);
	}

	public List<SetlistResponse.SongItem> findSongs(Long setlistId) {
		String sql = """
			SELECT item.song_id, item.position_no, s.title, s.duration_ms, sm.display_image_url
			FROM SETLIST_ITEM item
			JOIN SONG s ON s.song_id = item.song_id
			LEFT JOIN SONG_METADATA sm ON sm.song_id = s.song_id
			WHERE item.setlist_id = :setlistId
			ORDER BY item.position_no, item.setlist_item_id
			""";
		List<SetlistSongRow> rows = jdbcTemplate.query(sql, Map.of("setlistId", setlistId), (resultSet, rowNumber) ->
			new SetlistSongRow(
				resultSet.getLong("song_id"), resultSet.getString("title"),
				resultSet.getString("display_image_url"), resultSet.getObject("duration_ms", Long.class),
				resultSet.getInt("position_no")
			)
		);
		Map<Long, List<String>> artists = findArtists(rows.stream().map(SetlistSongRow::songId).toList());
		return rows.stream().map(row -> new SetlistResponse.SongItem(
			row.songId(), row.title(), artists.getOrDefault(row.songId(), List.of()),
			row.imageUrl(), row.durationMs(), row.position()
		)).toList();
	}

	private void normalizePositions(Long setlistId) {
		List<Long> songIds = findSongIds(setlistId);
		for (int index = 0; index < songIds.size(); index++) {
			jdbcTemplate.update(
				"UPDATE SETLIST_ITEM SET position_no = :position WHERE setlist_id = :setlistId AND song_id = :songId",
				Map.of("position", index + 1, "setlistId", setlistId, "songId", songIds.get(index))
			);
		}
	}

	private void touch(Long setlistId) {
		jdbcTemplate.update(
			"UPDATE SETLIST SET updated_at = CURRENT_TIMESTAMP(6) WHERE setlist_id = :setlistId",
			Map.of("setlistId", setlistId)
		);
	}

	private long count(String sql, Map<String, ?> params) {
		Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
		return count == null ? 0 : count;
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

	private long nextId() {
		return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
	}

	public record SetlistRow(Long setlistId, String name, LocalDateTime createdAt, LocalDateTime updatedAt) {
	}

	public record SetlistSummaryRow(
		Long setlistId, String name, int songCount, LocalDateTime createdAt, LocalDateTime updatedAt
	) {
	}

	private record SetlistSongRow(
		Long songId, String title, String imageUrl, Long durationMs, int position
	) {
	}
}
