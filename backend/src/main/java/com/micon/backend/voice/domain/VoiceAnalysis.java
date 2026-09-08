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
@Table(name = "voice_analysis")
public class VoiceAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "voice_analysis_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false)
    private VoiceRecording recording;

    @Column(name = "job_id", unique = true, length = 255)
    private String jobId;

    @Column(name = "song_id")
    private Long songId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoiceAnalysisMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VoiceAnalysisStatus status;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "analysis_version", length = 50)
    private String analysisVersion;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", length = 30)
    private VoiceQualityStatus qualityStatus;

    protected VoiceAnalysis() {
    }

    public VoiceAnalysis(User user, VoiceRecording recording, Long songId, VoiceAnalysisMethod method) {
        if (method == VoiceAnalysisMethod.SONG && songId == null) {
            throw new IllegalArgumentException("songId is required for SONG analysis");
        }
        this.user = user;
        this.recording = recording;
        this.songId = songId;
        this.method = method;
        this.status = VoiceAnalysisStatus.READY;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public VoiceRecording getRecording() { return recording; }
    public String getJobId() { return jobId; }
    public Long getSongId() { return songId; }
    public VoiceAnalysisMethod getMethod() { return method; }
    public VoiceAnalysisStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public String getAnalysisVersion() { return analysisVersion; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public VoiceQualityStatus getQualityStatus() { return qualityStatus; }

    public void assignJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        this.jobId = jobId;
    }

    public void start(String jobId, LocalDateTime startedAt) {
        if (status != VoiceAnalysisStatus.READY) {
            throw new IllegalStateException("only READY analysis can be started");
        }
        assignJobId(jobId);
        this.status = VoiceAnalysisStatus.ANALYZING;
        this.startedAt = startedAt;
        this.failureCode = null;
        this.failureReason = null;
    }

    public void fail(String failureCode, String failureReason, LocalDateTime completedAt) {
        if (status != VoiceAnalysisStatus.READY && status != VoiceAnalysisStatus.ANALYZING) {
            throw new IllegalStateException("analysis cannot fail from " + status);
        }
        this.status = VoiceAnalysisStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.completedAt = completedAt;
    }

    public void complete(String analysisVersion, VoiceQualityStatus qualityStatus, LocalDateTime completedAt) {
        if (status != VoiceAnalysisStatus.ANALYZING) {
            throw new IllegalStateException("only ANALYZING analysis can be completed");
        }
        this.status = VoiceAnalysisStatus.COMPLETED;
        this.analysisVersion = analysisVersion;
        this.qualityStatus = qualityStatus;
        this.completedAt = completedAt;
    }

    public void insufficientData(String failureCode, String failureReason,
                                 VoiceQualityStatus qualityStatus, LocalDateTime completedAt) {
        if (status != VoiceAnalysisStatus.ANALYZING) {
            throw new IllegalStateException("only ANALYZING analysis can report insufficient data");
        }
        this.status = VoiceAnalysisStatus.INSUFFICIENT_DATA;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.qualityStatus = qualityStatus == null ? VoiceQualityStatus.INSUFFICIENT_VOICE : qualityStatus;
        this.completedAt = completedAt;
    }
}
