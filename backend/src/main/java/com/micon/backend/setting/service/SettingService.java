package com.micon.backend.setting.service;

import com.micon.backend.setting.api.SettingResponse;
import com.micon.backend.setting.repository.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingService {

	private final SettingRepository settingRepository;

	public SettingService(SettingRepository settingRepository) {
		this.settingRepository = settingRepository;
	}

	@Transactional(readOnly = true)
	public SettingResponse getSettings(long userId) {
		return new SettingResponse(settingRepository.findSaveOriginalRecording(userId));
	}

	@Transactional
	public SettingResponse updateSettings(long userId, boolean saveOriginalRecording) {
		settingRepository.saveOriginalRecording(userId, saveOriginalRecording);
		return new SettingResponse(saveOriginalRecording);
	}
}
