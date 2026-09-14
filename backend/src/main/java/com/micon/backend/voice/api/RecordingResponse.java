package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.RecordingUploadStatus;
import com.micon.backend.voice.domain.RecordingStorageMode;
import com.micon.backend.voice.domain.VoiceRecording;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public final class RecordingResponse {
    private RecordingResponse() { }

    public record Page(List<Detail> content, int page, int size, long totalElements, int totalPages) { }

    public record Detail(Long recordingId, short attemptNo, RecordingStorageMode storageMode,
                         String mediaType, Long fileBytes,
                         Integer durationMs, Integer sampleRate, boolean keepOriginal,
                         RecordingUploadStatus uploadStatus, LocalDateTime deleteScheduledAt,
                         LocalDateTime createdAt) {
        public static Detail from(VoiceRecording recording) {
            return new Detail(recording.getId(), recording.getAttemptNo(), recording.getStorageMode(), recording.getMediaType(),
                    recording.getFileBytes(), recording.getDurationMs(), recording.getSampleRate(),
                    recording.isKeepOriginal(), recording.getUploadStatus(), recording.getDeleteScheduledAt(),
                    recording.getCreatedAt());
        }
    }

    public record Playback(Long recordingId, String contentUrl, String mediaType, Instant expiresAt) { }
}
