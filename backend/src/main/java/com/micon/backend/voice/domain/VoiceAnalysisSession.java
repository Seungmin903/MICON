package com.micon.backend.voice.domain;

import com.micon.backend.user.domain.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_analysis_session")
public class VoiceAnalysisSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "song_id") private Long songId;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "range_status", nullable = false, length = 30) private String rangeStatus;
    @Column(name = "timbre_status", nullable = false, length = 30) private String timbreStatus;
    @Column(name = "artist_search_status", nullable = false, length = 30) private String artistSearchStatus;
    @Column(name = "save_status", nullable = false, length = 30) private String saveStatus;
    @Column(name = "result_version", nullable = false) private long resultVersion;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected VoiceAnalysisSession() {}
    public VoiceAnalysisSession(User user, Long songId, LocalDateTime expiresAt) {
        if (user == null || expiresAt == null) throw new IllegalArgumentException("user and expiresAt are required");
        this.user = user; this.songId = songId; this.status = "CREATED";
        this.rangeStatus = "PENDING"; this.timbreStatus = "PENDING";
        this.artistSearchStatus = "PENDING"; this.saveStatus = "NOT_READY";
        this.expiresAt = expiresAt; this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt;
    }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public Long getSongId() { return songId; }
    public String getStatus() { return status; }
    public long getResultVersion() { return resultVersion; }
}
