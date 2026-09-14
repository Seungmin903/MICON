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
    @Column(name = "component_type", nullable = false, length = 30) private String componentType;
    @Column(name = "attempt_no", nullable = false) private int attemptNo;
    @Column(name = "ai_job_id", unique = true, length = 255) private String aiJobId;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "failure_code", length = 100) private String failureCode;
    @Column(name = "failure_reason", length = 1000) private String failureReason;
    @Column(name = "model_key", length = 50) private String modelKey;
    @Column(name = "model_version", length = 100) private String modelVersion;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    protected VoiceAnalysisComponentJob() {}
    public Long getId() { return id; }
    public VoiceAnalysisSession getSession() { return session; }
    public String getComponentType() { return componentType; }
    public String getStatus() { return status; }
}
