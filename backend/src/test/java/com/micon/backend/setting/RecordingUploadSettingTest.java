package com.micon.backend.setting;

import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.micon.backend.domain.setting.repository.SettingRepository;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.voice.service.RecordingUploadService;
import com.micon.backend.domain.voice.entity.VoiceRecording;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import com.micon.backend.domain.voice.storage.VoiceStorageProperties;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class RecordingUploadSettingTest {

	private SettingRepository settings;
	private RecordingUploadService service;
	private AtomicReference<VoiceRecording> savedRecording;

	@BeforeEach
	void setUp() {
		User user = new User("setting@micon.com", "encoded-password");
		ReflectionTestUtils.setField(user, "id", 7L);
		UserRepository users = mock(UserRepository.class);
		when(users.findByIdAndStatus(7L, UserStatus.ACTIVE)).thenReturn(Optional.of(user));

		savedRecording = new AtomicReference<>();
		VoiceRecordingRepository recordings = mock(VoiceRecordingRepository.class);
		when(recordings.save(any(VoiceRecording.class))).thenAnswer(invocation -> {
			VoiceRecording recording = invocation.getArgument(0);
			ReflectionTestUtils.setField(recording, "id", 11L);
			savedRecording.set(recording);
			return recording;
		});

		settings = mock(SettingRepository.class);
		service = new RecordingUploadService(
			users,
			recordings,
			mock(VoiceStorage.class),
			new VoiceStorageProperties("bucket", "ap-northeast-2", "recordings", 1_000_000),
			settings
		);
	}

	@Test
	void usesSavedSettingWhenUploadDoesNotOverrideIt() {
		when(settings.findSaveOriginalRecording(7L)).thenReturn(true);

		service.upload(7L, audio(), (short) 1, null);

		assertThat(savedRecording.get().isKeepOriginal()).isTrue();
	}

	@Test
	void explicitUploadValueOverridesSavedSetting() {
		when(settings.findSaveOriginalRecording(7L)).thenReturn(true);

		service.upload(7L, audio(), (short) 1, false);

		assertThat(savedRecording.get().isKeepOriginal()).isFalse();
	}

	private MockMultipartFile audio() {
		return new MockMultipartFile("file", "voice.wav", "audio/wav", new byte[] {1, 2, 3});
	}
}
