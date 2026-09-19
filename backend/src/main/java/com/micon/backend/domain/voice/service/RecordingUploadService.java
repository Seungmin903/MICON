package com.micon.backend.domain.voice.service;

import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.setting.repository.SettingRepository;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.voice.storage.VoiceStorage;
import com.micon.backend.domain.voice.storage.VoiceStorageProperties;
import com.micon.backend.domain.voice.entity.VoiceRecording;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class RecordingUploadService {
    private static final Set<String> SUPPORTED_TYPES = Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4");
    private final UserRepository users;
    private final VoiceRecordingRepository recordings;
    private final VoiceStorage storage;
    private final VoiceStorageProperties properties;
    private final SettingRepository settings;

    public RecordingUploadService(UserRepository users, VoiceRecordingRepository recordings,
                                  VoiceStorage storage, VoiceStorageProperties properties,
                                  SettingRepository settings) {
        this.users = users;
        this.recordings = recordings;
        this.storage = storage;
        this.properties = properties;
        this.settings = settings;
    }

    @Transactional
    public Long upload(long userId, MultipartFile file, short attemptNo, Boolean keepOriginalOverride) {
        validate(file, attemptNo);
        User user = users.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
        boolean keepOriginal = keepOriginalOverride != null
                ? keepOriginalOverride
                : settings.findSaveOriginalRecording(userId);
        String key = properties.prefix() + "/" + userId + "/" + UUID.randomUUID();
        VoiceRecording recording = recordings.save(new VoiceRecording(user, attemptNo, key, file.getContentType(), keepOriginal));
        try {
            try (InputStream content = file.getInputStream()) {
                storage.upload(key, content, file.getSize(), file.getContentType());
            }
            try (InputStream content = file.getInputStream()) {
                recording.completeUpload(sha256(content), file.getSize());
            }
            return recording.getId();
        } catch (Exception exception) {
            try { storage.delete(key); } catch (Exception ignored) { }
            throw new BusinessException(ErrorCode.RECORDING_UPLOAD_FAILED);
        }
    }

    private void validate(MultipartFile file, short attemptNo) {
        if (file == null || file.isEmpty() || file.getSize() > properties.maxFileBytes()
                || !SUPPORTED_TYPES.contains(file.getContentType()) || attemptNo < 1) {
            throw new BusinessException(ErrorCode.INVALID_RECORDING_FILE);
        }
    }

    private String sha256(InputStream content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = content.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
