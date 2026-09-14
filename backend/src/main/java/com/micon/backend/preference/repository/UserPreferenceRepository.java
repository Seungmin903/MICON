package com.micon.backend.preference.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserPreferenceRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public UserPreferenceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Long> findGenreIds(long userId) {
		return jdbcTemplate.queryForList("""
			SELECT genre_id
			FROM user_genre_preference
			WHERE user_id = :userId
			ORDER BY genre_id
			""", Map.of("userId", userId), Long.class);
	}

	public List<Long> findArtistIds(long userId) {
		return jdbcTemplate.queryForList("""
			SELECT singer_id
			FROM user_singer_preference
			WHERE user_id = :userId
			ORDER BY singer_id
			""", Map.of("userId", userId), Long.class);
	}

	public boolean isOnboardingCompleted(long userId) {
		Long count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM user_setting setting
			WHERE setting.user_id = :userId
			  AND EXISTS (
			      SELECT 1 FROM user_genre_preference genre
			      WHERE genre.user_id = setting.user_id
			  )
			  AND EXISTS (
			      SELECT 1 FROM user_singer_preference singer
			      WHERE singer.user_id = setting.user_id
			  )
			""", Map.of("userId", userId), Long.class);
		return count != null && count > 0;
	}

	public void replace(long userId, List<Long> genreIds, List<Long> artistIds) {
		Map<String, Object> user = Map.of("userId", userId);
		jdbcTemplate.update("DELETE FROM user_genre_preference WHERE user_id = :userId", user);
		jdbcTemplate.update("DELETE FROM user_singer_preference WHERE user_id = :userId", user);

		for (long genreId : genreIds) {
			jdbcTemplate.update("""
				INSERT INTO user_genre_preference(user_id, genre_id)
				VALUES (:userId, :genreId)
				""", Map.of("userId", userId, "genreId", genreId));
		}
		for (long artistId : artistIds) {
			jdbcTemplate.update("""
				INSERT INTO user_singer_preference(user_id, singer_id)
				VALUES (:userId, :artistId)
				""", Map.of("userId", userId, "artistId", artistId));
		}
	}

	public void markOnboardingCompleted(long userId) {
		Map<String, Object> parameters = Map.of("userId", userId);
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM user_setting WHERE user_id = :userId",
			parameters,
			Long.class
		);
		if (count == null || count == 0) {
			jdbcTemplate.update("""
				INSERT INTO user_setting(user_id, save_original_recording)
				VALUES (:userId, FALSE)
				""", parameters);
		}
	}
}
