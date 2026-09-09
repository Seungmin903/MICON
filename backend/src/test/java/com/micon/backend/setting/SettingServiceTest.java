package com.micon.backend.setting;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.setting.repository.SettingRepository;
import com.micon.backend.setting.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SettingServiceTest {

	private JdbcTemplate jdbcTemplate;
	private SettingService service;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:setting-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		jdbcTemplate.execute("""
			CREATE TABLE USER_SETTING (
			  user_id BIGINT PRIMARY KEY,
			  save_original_recording BIGINT NULL,
			  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
			)
			""");
		service = new SettingService(new SettingRepository(new NamedParameterJdbcTemplate(dataSource)));
	}

	@Test
	void returnsOffWhenUserHasNoSavedSetting() {
		assertThat(service.getSettings(7L).saveOriginalRecording()).isFalse();
	}

	@Test
	void treatsNullStoredValueAsOff() {
		jdbcTemplate.update("INSERT INTO USER_SETTING(user_id, save_original_recording) VALUES (?, NULL)", 7L);

		assertThat(service.getSettings(7L).saveOriginalRecording()).isFalse();
	}

	@Test
	void createsAndUpdatesOriginalRecordingSetting() {
		assertThat(service.updateSettings(7L, true).saveOriginalRecording()).isTrue();
		assertThat(service.getSettings(7L).saveOriginalRecording()).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USER_SETTING", Integer.class)).isEqualTo(1);

		assertThat(service.updateSettings(7L, false).saveOriginalRecording()).isFalse();
		assertThat(service.getSettings(7L).saveOriginalRecording()).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USER_SETTING", Integer.class)).isEqualTo(1);
	}
}
