package com.micon.backend.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.api.RecordingResponse;
import com.micon.backend.voice.domain.RecordingUploadStatus;
import com.micon.backend.voice.domain.VoiceRecording;
import com.micon.backend.voice.domain.VoiceRecordingRepository;
import com.micon.backend.voice.storage.VoiceStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Service
public class RecordingQueryService {
    private final UserRepository users;
    private final VoiceRecordingRepository recordings;
    private final VoiceStorage storage;
    private final Duration playbackUrlTtl;
    private final Clock clock;

    public RecordingQueryService(UserRepository users, VoiceRecordingRepository recordings, VoiceStorage storage,
                                 @Value("${voice.storage.playback-url-ttl:10m}") Duration playbackUrlTtl,
                                 Clock clock) {
        this.users = users;
        this.recordings = recordings;
        this.storage = storage;
        this.playbackUrlTtl = playbackUrlTtl;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RecordingResponse.Page list(long userId, int page, int size) {
        ensureActiveUser(userId);
        validatePage(page, size);
        var result = recordings.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(page, size));
        return new RecordingResponse.Page(result.getContent().stream().map(RecordingResponse.Detail::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public RecordingResponse.Detail get(long userId, long recordingId) {
        ensureActiveUser(userId);
        return RecordingResponse.Detail.from(owned(userId, recordingId));
    }

    @Transactional(readOnly = true)
    public RecordingResponse.Playback playback(long userId, long recordingId) {
        ensureActiveUser(userId);
        VoiceRecording recording = owned(userId, recordingId);
        if (recording.getUploadStatus() != RecordingUploadStatus.UPLOADED) {
            throw new BusinessException(ErrorCode.RECORDING_NOT_READY);
        }
        String contentUrl = storage.createDownloadUrl(recording.getStoragePath(), playbackUrlTtl).toString();
        return new RecordingResponse.Playback(recording.getId(), contentUrl, recording.getMediaType(),
                clock.instant().plus(playbackUrlTtl));
    }

    private VoiceRecording owned(long userId, long recordingId) {
        return recordings.findByIdAndUserIdAndDeletedAtIsNull(recordingId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORDING_NOT_FOUND));
    }

    private void ensureActiveUser(long userId) {
        if (users.findByIdAndStatus(userId, UserStatus.ACTIVE).isEmpty()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1 이상 100 이하여야 합니다.");
        }
    }
}
