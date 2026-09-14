package com.micon.backend.voice.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_analysis_component_job")
public class VoiceAnalysisComponentJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "component_job_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private VoiceAnalysisSession session;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recording_id") private VoiceRecording recording;
    @Column(name = "component_type", nullable = false, length = 30) private String componentType;
    @Column(name = "attempt_no", nullable = false) private int attemptNo;
    @Column(name = "request_id", unique = true, length = 100) private String requestId;
    @Column(name = "ai_job_id", unique = true, length = 255) private String aiJobId;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "failure_code", length = 100) private String failureCode;
    @Column(name = "failure_reason", length = 1000) private String failureReason;
    @Column(name = "model_key", length = 50) private String modelKey;
    @Column(name = "model_version", length = 100) private String modelVersion;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    protected VoiceAnalysisComponentJob() {}

    public VoiceAnalysisComponentJob(VoiceAnalysisSession session, VoiceRecording recording,
                                     String componentType, int attemptNo, String requestId) {
        if (session == null || recording == null || componentType == null || componentType.isBlank()
                || attemptNo < 1 || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("valid voice analysis job data is required");
        }
        this.session = session;
        this.recording = recording;
        this.componentType = componentType;
        this.attemptNo = attemptNo;
        this.requestId = requestId;
        this.status = "PENDING";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public VoiceAnalysisSession getSession() { return session; }
    public VoiceRecording getRecording() { return recording; }
    public String getComponentType() { return componentType; }
    public int getAttemptNo() { return attemptNo; }
    public String getRequestId() { return requestId; }
    public String getAiJobId() { return aiJobId; }
    public String getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public String getModelKey() { return modelKey; }
    public String getModelVersion() { return modelVersion; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void queued(String aiJobId, LocalDateTime at) {
        if (aiJobId == null || aiJobId.isBlank()) {
            throw new IllegalStateException("only pending job can be queued");
        }
        bindAiJobId(aiJobId);
        if (isTerminal() || "RUNNING".equals(status)) return;
        if (!"PENDING".equals(status)) throw new IllegalStateException("only pending job can be queued");
        this.status = "QUEUED";
        this.startedAt = at;
        this.updatedAt = at;
    }

    public void bindAiJobId(String aiJobId) {
        if (aiJobId == null || aiJobId.isBlank()
                || (this.aiJobId != null && !this.aiJobId.equals(aiJobId))) {
            throw new IllegalStateException("AI job id does not match");
        }
        this.aiJobId = aiJobId;
        this.updatedAt = LocalDateTime.now();
    }

    public void running(LocalDateTime at) {
        if (!"PENDING".equals(status) && !"QUEUED".equals(status)) {
            throw new IllegalStateException("job cannot start");
        }
        this.status = "RUNNING";
        if (startedAt == null) this.startedAt = at;
        this.updatedAt = at;
    }

    public void complete(String modelKey, String modelVersion, LocalDateTime at) {
        if (isTerminal()) return;
        this.modelKey = modelKey;
        this.modelVersion = modelVersion;
        this.status = "COMPLETED";
        this.completedAt = at;
        this.updatedAt = at;
    }

    public void fail(String code, String reason, LocalDateTime at) {
        if (isTerminal()) return;
        this.failureCode = code;
        this.failureReason = reason;
        this.status = "FAILED";
        this.completedAt = at;
        this.updatedAt = at;
    }

    public void insufficient(String code, String reason, LocalDateTime at) {
        if (isTerminal()) return;
        this.failureCode = code;
        this.failureReason = reason;
        this.status = "INSUFFICIENT_DATA";
        this.completedAt = at;
        this.updatedAt = at;
    }

    public void cancel(LocalDateTime at) {
        if (isTerminal()) return;
        this.status = "CANCELED";
        this.completedAt = at;
        this.updatedAt = at;
    }

    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "INSUFFICIENT_DATA".equals(status) || "CANCELED".equals(status);
    }
}
