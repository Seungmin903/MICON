package com.micon.backend.domain.voice.entity;

import com.micon.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_profile_save_job")
public class VoiceProfileSaveJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "save_job_id") private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 100) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private VoiceAnalysisSession session;
    @Column(name = "idempotency_key", nullable = false, length = 100) private String idempotencyKey;
    @Column(name = "requested_result_version", nullable = false) private long requestedResultVersion;
    @Column(nullable = false, length = 30) private String status;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "profile_id") private UserVoiceProfile profile;
    @Column(name = "saved_profile_version") private Long savedProfileVersion;
    @Column(name = "failure_code", length = 100) private String failureCode;
    @Column(name = "failure_reason", length = 1000) private String failureReason;
    @Column(nullable = false) private boolean retryable;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    protected VoiceProfileSaveJob() {}
    public String getPublicId() { return publicId; }
    public String getStatus() { return status; }
}
