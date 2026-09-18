package com.micon.backend.domain.voice.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_analysis_job")
public class VoiceAnalysisJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_job_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private VoiceAnalysisSession session;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_id") private VoiceRecording recording;
    @Column(name = "request_id", nullable = false, unique = true, length = 100) private String requestId;
    @Column(name = "audio_sha256", length = 64, columnDefinition = "char(64)") private String audioSha256;
    @Column(name = "ai_job_id", unique = true, length = 255) private String aiJobId;
    @Column(name = "analysis_type", nullable = false, length = 30) private String analysisType;
    @Column(name = "attempt_no", nullable = false) private int attemptNo;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "audio_expires_at") private LocalDateTime audioExpiresAt;
    @Column(nullable = false) private boolean duplicate;
    @Column(name = "failure_code", length = 100) private String failureCode;
    @Column(name = "failure_reason", length = 1000) private String failureReason;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected VoiceAnalysisJob() { }

    public VoiceAnalysisJob(VoiceAnalysisSession session, VoiceRecording recording,
                            String requestId, int attemptNo) {
        this(session, recording, requestId, null, attemptNo);
    }

    public VoiceAnalysisJob(VoiceAnalysisSession session, VoiceRecording recording, String requestId,
                            String audioSha256, int attemptNo) {
        if (session == null || requestId == null || requestId.isBlank() || attemptNo < 1
                || (audioSha256 != null && audioSha256.length() != 64)) {
            throw new IllegalArgumentException("valid parent analysis job data is required");
        }
        this.session = session;
        this.recording = recording;
        this.requestId = requestId;
        this.audioSha256 = audioSha256;
        this.analysisType = "TIMBRE";
        this.attemptNo = attemptNo;
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public VoiceAnalysisSession getSession() { return session; }
    public VoiceRecording getRecording() { return recording; }
    public String getRequestId() { return requestId; }
    public String getAudioSha256() { return audioSha256; }
    public String getAiJobId() { return aiJobId; }
    public int getAttemptNo() { return attemptNo; }
    public String getStatus() { return status; }
    public LocalDateTime getAudioExpiresAt() { return audioExpiresAt; }
    public boolean isDuplicate() { return duplicate; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean canDispatch() {
        return "PENDING".equals(status) || "DISPATCH_UNKNOWN".equals(status);
    }

    public void queued(String jobId) {
        queued(jobId, "QUEUED", null, false, LocalDateTime.now());
    }

    public void queued(String jobId, String acceptedStatus, LocalDateTime expiresAt,
                       boolean duplicate, LocalDateTime at) {
        if (jobId == null || jobId.isBlank()
                || acceptedStatus == null || !java.util.Set.of("QUEUED", "RUNNING", "COMPLETED",
                        "FAILED", "INSUFFICIENT_DATA").contains(acceptedStatus)) {
            throw new IllegalStateException("AI did not accept the analysis job");
        }
        bindAiJobId(jobId);
        this.audioExpiresAt = expiresAt;
        this.duplicate = duplicate;
        // Completion is driven by validated callbacks, which may arrive before this receipt.
        if (terminal()) return;
        this.status = "RUNNING".equals(this.status) || "RUNNING".equals(acceptedStatus) ? "RUNNING" : "QUEUED";
        if (startedAt == null) startedAt = at;
        this.failureCode = null;
        this.failureReason = null;
        this.updatedAt = at;
    }

    public void bindAiJobId(String jobId) {
        if (jobId == null || jobId.isBlank() || (aiJobId != null && !aiJobId.equals(jobId))) {
            throw new IllegalStateException("AI job id does not match");
        }
        aiJobId = jobId;
        updatedAt = LocalDateTime.now();
    }

    public void running(LocalDateTime at) {
        if (!terminal()) {
            status = "RUNNING";
            if (startedAt == null) startedAt = at;
            updatedAt = at;
        }
    }

    public void dispatchUnknown(String reason) { dispatchUnknown(reason, LocalDateTime.now()); }

    public void dispatchUnknown(String reason, LocalDateTime at) {
        if (!canDispatch()) return;
        status = "DISPATCH_UNKNOWN";
        failureCode = "AI_DISPATCH_UNKNOWN";
        failureReason = reason;
        updatedAt = at;
    }

    public void complete(LocalDateTime at) {
        status = "COMPLETED";
        completedAt = at;
        updatedAt = at;
    }

    public void fail(String code, String reason, LocalDateTime at) {
        if (terminal()) return;
        status = "FAILED";
        failureCode = code;
        failureReason = reason;
        completedAt = at;
        updatedAt = at;
    }

    public void cancel(LocalDateTime at) {
        if (terminal()) return;
        status = "CANCELED";
        completedAt = at;
        updatedAt = at;
    }

    public void retry(LocalDateTime at) {
        status = "RUNNING";
        completedAt = null;
        failureCode = null;
        failureReason = null;
        updatedAt = at;
    }

    public boolean terminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }
}
