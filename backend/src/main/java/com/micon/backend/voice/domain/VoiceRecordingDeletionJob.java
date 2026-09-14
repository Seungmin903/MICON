package com.micon.backend.voice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_recording_deletion_job")
public class VoiceRecordingDeletionJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deletion_job_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false, unique = true)
    private VoiceRecording recording;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected VoiceRecordingDeletionJob() { }

    public VoiceRecordingDeletionJob(VoiceRecording recording, LocalDateTime requestedAt) {
        this.recording = recording;
        this.status = "PENDING";
        this.nextAttemptAt = requestedAt;
        this.createdAt = requestedAt;
        this.updatedAt = requestedAt;
    }

    public Long getId() { return id; }
    public VoiceRecording getRecording() { return recording; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void claim(LocalDateTime at) {
        status = "PROCESSING";
        attemptCount++;
        updatedAt = at;
    }

    public void complete(LocalDateTime at) {
        status = "COMPLETED";
        failureReason = null;
        updatedAt = at;
    }

    public void fail(String reason, LocalDateTime retryAt, LocalDateTime at) {
        status = "FAILED";
        failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000));
        nextAttemptAt = retryAt;
        updatedAt = at;
    }
}
