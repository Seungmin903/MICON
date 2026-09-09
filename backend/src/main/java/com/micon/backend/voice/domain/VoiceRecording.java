package com.micon.backend.voice.domain;

import com.micon.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "voice_recording")
public class VoiceRecording {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recording_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "attempt_no", nullable = false)
    private short attemptNo;

    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Column(name = "file_bytes")
    private Long fileBytes;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "sample_rate")
    private Integer sampleRate;

    @Column(name = "keep_original", nullable = false)
    private boolean keepOriginal;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private RecordingUploadStatus uploadStatus;

    @Column(name = "delete_scheduled_at")
    private LocalDateTime deleteScheduledAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected VoiceRecording() {
    }

    public VoiceRecording(User user, short attemptNo, String storagePath, String mediaType, boolean keepOriginal) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        this.user = user;
        this.attemptNo = attemptNo;
        this.storagePath = storagePath;
        this.mediaType = mediaType;
        this.keepOriginal = keepOriginal;
        this.uploadStatus = RecordingUploadStatus.UPLOADING;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public short getAttemptNo() { return attemptNo; }
    public String getStoragePath() { return storagePath; }
    public String getMediaType() { return mediaType; }
    public String getSha256() { return sha256; }
    public Long getFileBytes() { return fileBytes; }
    public Integer getDurationMs() { return durationMs; }
    public Integer getSampleRate() { return sampleRate; }
    public boolean isKeepOriginal() { return keepOriginal; }
    public RecordingUploadStatus getUploadStatus() { return uploadStatus; }
    public LocalDateTime getDeleteScheduledAt() { return deleteScheduledAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void completeUpload(String sha256, long fileBytes) {
        this.sha256 = sha256;
        this.fileBytes = fileBytes;
        this.uploadStatus = RecordingUploadStatus.UPLOADED;
    }

    public void markDeletePending(LocalDateTime scheduledAt) {
        if (uploadStatus == RecordingUploadStatus.DELETED) {
            throw new IllegalStateException("recording is already deleted");
        }
        this.uploadStatus = RecordingUploadStatus.DELETE_PENDING;
        this.deleteScheduledAt = scheduledAt;
    }

    public void markDeleted(LocalDateTime deletedAt) {
        if (uploadStatus != RecordingUploadStatus.DELETE_PENDING) {
            throw new IllegalStateException("only DELETE_PENDING recording can be deleted");
        }
        this.uploadStatus = RecordingUploadStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
