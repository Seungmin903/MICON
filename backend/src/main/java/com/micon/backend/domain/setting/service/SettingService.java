package com.micon.backend.domain.setting.service;

import com.micon.backend.domain.setting.repository.SettingRepository;
import com.micon.backend.domain.setting.dto.SettingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원의 원본 녹음 저장 설정을 조회하고 변경하는 설정 유스케이스를 처리한다. */
@Service
public class SettingService {

	private final SettingRepository settingRepository;

	// 기능: 필요한 의존성과 초기값을 받아 SettingService 객체를 생성한다.
	public SettingService(SettingRepository settingRepository) {
		this.settingRepository = settingRepository;
	}

	@Transactional(readOnly = true)
	// 기능: getSettings 조회 유스케이스를 수행하고 응답 데이터를 구성한다.
	public SettingResponse getSettings(long userId) {
		return new SettingResponse(settingRepository.findSaveOriginalRecording(userId));
	}

	@Transactional
	// 기능: updateSettings 업무 규칙을 검증하고 상태를 변경한다.
	public SettingResponse updateSettings(long userId, boolean saveOriginalRecording) {
		settingRepository.saveOriginalRecording(userId, saveOriginalRecording);
		return new SettingResponse(saveOriginalRecording);
	}
}
