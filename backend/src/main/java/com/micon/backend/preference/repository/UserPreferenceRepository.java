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
		Boolean completed = jdbcTemplate.query("""
			SELECT preference_onboarding_completed
			FROM user_setting
			WHERE user_id = :userId
			""", Map.of("userId", userId), resultSet ->
			resultSet.next() && resultSet.getBoolean("preference_onboarding_completed"));
		return Boolean.TRUE.equals(completed);
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
		int updated = jdbcTemplate.update("""
			UPDATE user_setting
			SET preference_onboarding_completed = TRUE,
			    updated_at = CURRENT_TIMESTAMP
			WHERE user_id = :userId
			""", parameters);
		if (updated == 0) {
			jdbcTemplate.update("""
				INSERT INTO user_setting(
				    user_id,
				    save_original_recording,
				    preference_onboarding_completed
				)
				VALUES (:userId, FALSE, TRUE)
				""", parameters);
		}
	}
}
