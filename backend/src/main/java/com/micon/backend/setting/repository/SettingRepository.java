package com.micon.backend.setting.repository;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SettingRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean findSaveOriginalRecording(long userId) {
		List<Boolean> settings = jdbcTemplate.query(
			"SELECT save_original_recording FROM USER_SETTING WHERE user_id = :userId",
			Map.of("userId", userId),
			(resultSet, rowNumber) -> {
				Long value = resultSet.getObject("save_original_recording", Long.class);
				return value != null && value != 0;
			}
		);
		return !settings.isEmpty() && settings.getFirst();
	}

	public void saveOriginalRecording(long userId, boolean saveOriginalRecording) {
		Map<String, Object> parameters = Map.of(
			"userId", userId,
			"saveOriginalRecording", saveOriginalRecording ? 1 : 0
		);
		int updated = jdbcTemplate.update("""
			UPDATE USER_SETTING
			SET save_original_recording = :saveOriginalRecording,
			    updated_at = CURRENT_TIMESTAMP
			WHERE user_id = :userId
			""", parameters);
		if (updated > 0) {
			return;
		}

		try {
			jdbcTemplate.update("""
				INSERT INTO USER_SETTING(user_id, save_original_recording)
				VALUES (:userId, :saveOriginalRecording)
				""", parameters);
		} catch (DuplicateKeyException exception) {
			jdbcTemplate.update("""
				UPDATE USER_SETTING
				SET save_original_recording = :saveOriginalRecording,
				    updated_at = CURRENT_TIMESTAMP
				WHERE user_id = :userId
				""", parameters);
		}
	}
}
