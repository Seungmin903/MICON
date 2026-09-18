package com.micon.backend.domain.setting.repository;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** user_setting에서 원본 녹음 저장 여부를 조회하고 없으면 생성, 있으면 갱신한다. */
@Repository
public class SettingRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 기능: 필요한 의존성과 초기값을 받아 SettingRepository 객체를 생성한다.
	public SettingRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// 기능: findSaveOriginalRecording 조건으로 데이터베이스를 조회한다.
	public boolean findSaveOriginalRecording(long userId) {
		List<Boolean> settings = jdbcTemplate.query(
			"SELECT save_original_recording FROM user_setting WHERE user_id = :userId",
			Map.of("userId", userId),
			(resultSet, rowNumber) -> {
				Long value = resultSet.getObject("save_original_recording", Long.class);
				return value != null && value != 0;
			}
		);
		return !settings.isEmpty() && settings.getFirst();
	}

	// 기능: saveOriginalRecording 데이터를 데이터베이스에 저장한다.
	public void saveOriginalRecording(long userId, boolean saveOriginalRecording) {
		Map<String, Object> parameters = Map.of(
			"userId", userId,
			"saveOriginalRecording", saveOriginalRecording ? 1 : 0
		);
		int updated = jdbcTemplate.update("""
			UPDATE user_setting
			SET save_original_recording = :saveOriginalRecording,
			    updated_at = CURRENT_TIMESTAMP
			WHERE user_id = :userId
			""", parameters);
		if (updated > 0) {
			return;
		}

		try {
			jdbcTemplate.update("""
				INSERT INTO user_setting(user_id, save_original_recording)
				VALUES (:userId, :saveOriginalRecording)
				""", parameters);
		} catch (DuplicateKeyException exception) {
			jdbcTemplate.update("""
				UPDATE user_setting
				SET save_original_recording = :saveOriginalRecording,
				    updated_at = CURRENT_TIMESTAMP
				WHERE user_id = :userId
				""", parameters);
		}
	}
}
