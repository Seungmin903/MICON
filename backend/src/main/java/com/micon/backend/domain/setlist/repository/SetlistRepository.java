package com.micon.backend.domain.setlist.repository;

import com.micon.backend.domain.setlist.dto.SetlistResponse;
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

/** setlist와 setlist_song 테이블을 조회·변경하고 저장된 곡 순서를 유지한다. */
@Repository
public class SetlistRepository {

	private static final int ID_INSERT_ATTEMPTS = 3;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 SetlistRepository 객체를 생성한다.
	public SetlistRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: create 데이터를 데이터베이스에 저장한다.
	public SetlistRow create(long userId, String name) {
		for (int attempt = 0; attempt < ID_INSERT_ATTEMPTS; attempt++) {
			long setlistId = nextId();
			try {
				jdbcTemplate.update(
					"INSERT INTO setlist(setlist_id, user_id, name) VALUES (:setlistId, :userId, :name)",
					Map.of("setlistId", setlistId, "userId", userId, "name", name)
				);
				return findOwned(setlistId, userId).orElseThrow();
			} catch (DuplicateKeyException ignored) {
				// The supplied schema has no auto increment for this key; retry a random positive BIGINT.
			}
		}
		throw new IllegalStateException("세트리스트 식별자를 생성하지 못했습니다.");
	}

	// 기능: findAll 조건으로 데이터베이스를 조회한다.
	public List<SetlistSummaryRow> findAll(long userId) {
		String sql = """
			SELECT sl.setlist_id, sl.name, sl.created_at, sl.updated_at, COUNT(item.setlist_item_id) AS song_count
			FROM setlist sl
			LEFT JOIN setlist_item item ON item.setlist_id = sl.setlist_id
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

	// 기능: findOwned 조건으로 데이터베이스를 조회한다.
	public Optional<SetlistRow> findOwned(Long setlistId, long userId) {
		return findOwned(setlistId, userId, false);
	}

	// 기능: findOwnedForUpdate 조건으로 데이터베이스를 조회한다.
	public Optional<SetlistRow> findOwnedForUpdate(Long setlistId, long userId) {
		return findOwned(setlistId, userId, true);
	}

	// 기능: findOwned 조건으로 데이터베이스를 조회한다.
	private Optional<SetlistRow> findOwned(Long setlistId, long userId, boolean forUpdate) {
		String sql = """
			SELECT setlist_id, name, created_at, updated_at
			FROM setlist
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

	// 기능: rename 대상 데이터를 데이터베이스에서 변경한다.
	public SetlistRow rename(Long setlistId, long userId, String name) {
		jdbcTemplate.update(
			"UPDATE setlist SET name = :name, updated_at = CURRENT_TIMESTAMP(6) "
				+ "WHERE setlist_id = :setlistId AND user_id = :userId",
			Map.of("name", name, "setlistId", setlistId, "userId", userId)
		);
		return findOwned(setlistId, userId).orElseThrow();
	}

	// 기능: delete 대상 데이터를 데이터베이스에서 삭제한다.
	public void delete(Long setlistId, long userId) {
		jdbcTemplate.update(
			"DELETE FROM setlist_item WHERE setlist_id = :setlistId",
			Map.of("setlistId", setlistId)
		);
		jdbcTemplate.update(
			"DELETE FROM setlist WHERE setlist_id = :setlistId AND user_id = :userId",
			Map.of("setlistId", setlistId, "userId", userId)
		);
	}

	// 기능: songExists에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public boolean songExists(String songId) {
		return count("SELECT COUNT(*) FROM song WHERE song_id = :songId", Map.of("songId", songId)) > 0;
	}

	// 기능: containsSong에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	public boolean containsSong(Long setlistId, String songId) {
		return count(
			"SELECT COUNT(*) FROM setlist_item WHERE setlist_id = :setlistId AND song_id = :songId",
			Map.of("setlistId", setlistId, "songId", songId)
		) > 0;
	}

	// 기능: addSong 데이터를 데이터베이스에 저장한다.
	public void addSong(Long setlistId, String songId) {
		if (containsSong(setlistId, songId)) {
			return;
		}
		Integer nextPosition = jdbcTemplate.queryForObject(
			"SELECT COALESCE(MAX(position_no), 0) + 1 FROM setlist_item WHERE setlist_id = :setlistId",
			Map.of("setlistId", setlistId),
			Integer.class
		);
		for (int attempt = 0; attempt < ID_INSERT_ATTEMPTS; attempt++) {
			try {
				jdbcTemplate.update(
					"INSERT INTO setlist_item(setlist_item_id, setlist_id, song_id, position_no) "
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

	// 기능: removeSong 대상 데이터를 데이터베이스에서 삭제한다.
	public void removeSong(Long setlistId, String songId) {
		int removed = jdbcTemplate.update(
			"DELETE FROM setlist_item WHERE setlist_id = :setlistId AND song_id = :songId",
			Map.of("setlistId", setlistId, "songId", songId)
		);
		if (removed > 0) {
			normalizePositions(setlistId);
			touch(setlistId);
		}
	}

	// 기능: reorderSongs 대상 데이터를 데이터베이스에서 변경한다.
	public void reorderSongs(Long setlistId, List<String> songIds) {
		// (setlist_id, position_no)는 유니크다. 1번과 2번을 바로 맞바꾸면
		// 첫 UPDATE가 기존 2번과 충돌하므로, 먼저 현재 범위를 벗어난 임시 순번을 쓴다.
		int temporaryPositionStart = songIds.size() + 1;
		for (int index = 0; index < songIds.size(); index++) {
			jdbcTemplate.update(
				"UPDATE setlist_item SET position_no = :position "
					+ "WHERE setlist_id = :setlistId AND song_id = :songId",
				Map.of("position", temporaryPositionStart + index, "setlistId", setlistId, "songId", songIds.get(index))
			);
		}
		for (int index = 0; index < songIds.size(); index++) {
			jdbcTemplate.update(
				"UPDATE setlist_item SET position_no = :position "
					+ "WHERE setlist_id = :setlistId AND song_id = :songId",
				Map.of("position", index + 1, "setlistId", setlistId, "songId", songIds.get(index))
			);
		}
		touch(setlistId);
	}

	// 기능: findSongIds 조건으로 데이터베이스를 조회한다.
	public List<String> findSongIds(Long setlistId) {
		return jdbcTemplate.queryForList(
			"SELECT song_id FROM setlist_item WHERE setlist_id = :setlistId ORDER BY position_no, setlist_item_id",
			Map.of("setlistId", setlistId),
			String.class
		);
	}

	// 기능: findSongs 조건으로 데이터베이스를 조회한다.
	public List<SetlistResponse.SongItem> findSongs(Long setlistId) {
		String sql = """
			SELECT item.song_id, item.position_no, s.title, s.duration_ms, sm.display_image_url
			FROM setlist_item item
			JOIN song s ON s.song_id = item.song_id
			LEFT JOIN song_metadata sm ON sm.song_id = s.song_id
			WHERE item.setlist_id = :setlistId
			ORDER BY item.position_no, item.setlist_item_id
			""";
		List<SetlistSongRow> rows = jdbcTemplate.query(sql, Map.of("setlistId", setlistId), (resultSet, rowNumber) ->
			new SetlistSongRow(
				resultSet.getString("song_id"), resultSet.getString("title"),
				resultSet.getString("display_image_url"), resultSet.getObject("duration_ms", Long.class),
				resultSet.getInt("position_no")
			)
		);
		Map<String, List<String>> artists = findArtists(rows.stream().map(SetlistSongRow::songId).toList());
		return rows.stream().map(row -> new SetlistResponse.SongItem(
			row.songId(), row.title(), artists.getOrDefault(row.songId(), List.of()),
			row.imageUrl(), row.durationMs(), row.position()
		)).toList();
	}

	// 기능: normalizePositions에 필요한 SQL 실행과 조회 결과 매핑을 수행한다.
	private void normalizePositions(Long setlistId) {
		List<String> songIds = findSongIds(setlistId);
		for (int index = 0; index < songIds.size(); index++) {
			jdbcTemplate.update(
				"UPDATE setlist_item SET position_no = :position WHERE setlist_id = :setlistId AND song_id = :songId",
				Map.of("position", index + 1, "setlistId", setlistId, "songId", songIds.get(index))
			);
		}
	}

	// 기능: touch 원본 값을 API 응답 또는 내부 데이터 형태로 변환한다.
	private void touch(Long setlistId) {
		jdbcTemplate.update(
			"UPDATE setlist SET updated_at = CURRENT_TIMESTAMP(6) WHERE setlist_id = :setlistId",
			Map.of("setlistId", setlistId)
		);
	}

	// 기능: count 조건으로 데이터베이스를 조회한다.
	private long count(String sql, Map<String, ?> params) {
		Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
		return count == null ? 0 : count;
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

	// 기능: nextId 저장에 사용할 새 식별자를 생성한다.
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
		String songId, String title, String imageUrl, Long durationMs, int position
	) {
	}
}
