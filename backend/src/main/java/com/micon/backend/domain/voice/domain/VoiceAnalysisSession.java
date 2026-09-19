package com.micon.backend.domain.voice.domain;

import com.micon.backend.domain.user.domain.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_analysis_session")
public class VoiceAnalysisSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "song_id") private String songId;
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
    public VoiceAnalysisSession(User user, String songId, LocalDateTime expiresAt) {
        if (user == null || expiresAt == null) throw new IllegalArgumentException("user and expiresAt are required");
        this.user = user; this.songId = songId; this.status = "CREATED";
        this.rangeStatus = "PENDING"; this.timbreStatus = "PENDING";
        this.artistSearchStatus = "PENDING"; this.saveStatus = "NOT_READY";
        this.expiresAt = expiresAt; this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt;
    }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getSongId() { return songId; }
    public String getStatus() { return status; }
    public String getRangeStatus() { return rangeStatus; }
    public String getTimbreStatus() { return timbreStatus; }
    public String getArtistSearchStatus() { return artistSearchStatus; }
    public long getResultVersion() { return resultVersion; }
    public String getSaveStatus() { return saveStatus; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void acceptRange(String measurementStatus) {
        this.rangeStatus = measurementStatus;
        this.resultVersion++;
        if (this.startedAt == null) this.startedAt = LocalDateTime.now();
        refreshOverallStatus();
        this.updatedAt = LocalDateTime.now();
    }

    public void beginRangeAnalysis() {
        if (isExpired() || "CANCELED".equals(status) || "SAVED".equals(status)) {
            throw new IllegalStateException("session cannot start range analysis");
        }
        rangeStatus = "UPLOADING";
        refreshOverallStatus();
        if (startedAt == null) startedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    public void queueRangeAnalysis() {
        rangeStatus = "ANALYZING";
        refreshOverallStatus();
        updatedAt = LocalDateTime.now();
    }

    public void failRangeAnalysis(LocalDateTime at) {
        rangeStatus = "FAILED";
        refreshOverallStatus();
        completedAt = at;
        updatedAt = at;
    }

    public void beginTimbreAnalysis() {
        if (isExpired() || "CANCELED".equals(status) || "SAVED".equals(status)) {
            throw new IllegalStateException("session cannot start timbre analysis");
        }
        timbreStatus = "UPLOADING";
        refreshOverallStatus();
        if (startedAt == null) startedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    public void acceptTimbreAnalysis() {
        timbreStatus = "ANALYZING";
        refreshOverallStatus();
        updatedAt = LocalDateTime.now();
    }

    public void completeTimbreAnalysis(LocalDateTime at) {
        timbreStatus = "AVAILABLE";
        resultVersion++;
        refreshOverallStatus();
        completedAt = at;
        updatedAt = at;
    }

    public void failTimbreAnalysis(boolean insufficient, LocalDateTime at) {
        timbreStatus = insufficient ? "INSUFFICIENT_DATA" : "FAILED";
        refreshOverallStatus();
        completedAt = at;
        updatedAt = at;
    }

    public void cancelTimbreAnalysis(LocalDateTime at) {
        timbreStatus = "FAILED";
        refreshOverallStatus();
        completedAt = at;
        updatedAt = at;
    }

    public void beginArtistSearch() {
        artistSearchStatus = "RUNNING";
        updatedAt = LocalDateTime.now();
    }

    public void completeArtistSearch(boolean empty, LocalDateTime at) {
        artistSearchStatus = empty ? "NO_RESULT" : "COMPLETED";
        updatedAt = at;
    }

    public void failArtistSearch(LocalDateTime at) {
        artistSearchStatus = "FAILED";
        updatedAt = at;
    }

    public void markSaved() {
        if (!"READY_TO_SAVE".equals(status) || !"AVAILABLE".equals(rangeStatus)
                || !"AVAILABLE".equals(timbreStatus)) {
            throw new IllegalStateException("session is not ready to save");
        }
        status = "SAVED";
        saveStatus = "SAVED";
        updatedAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return !expiresAt.isAfter(LocalDateTime.now()) || "EXPIRED".equals(status);
    }

    /**
     * 음역과 음색은 독립 작업이므로 한 작업의 완료 순서가 다른 작업의 상태를
     * 덮어쓰지 않도록 전체 세션 상태를 두 결과 상태에서 다시 계산한다.
     */
    private void refreshOverallStatus() {
        if ("AVAILABLE".equals(rangeStatus) && "AVAILABLE".equals(timbreStatus)) {
            status = "READY_TO_SAVE";
            return;
        }
        if ("AVAILABLE".equals(rangeStatus) || "AVAILABLE".equals(timbreStatus)) {
            status = "PARTIAL_READY";
            return;
        }
        if ("FAILED".equals(rangeStatus) || "FAILED".equals(timbreStatus)) {
            status = "FAILED";
            return;
        }
        if ("INSUFFICIENT_DATA".equals(rangeStatus) || "INSUFFICIENT_DATA".equals(timbreStatus)) {
            status = "INSUFFICIENT_DATA";
            return;
        }
        if ("UPLOADING".equals(rangeStatus) || "UPLOADING".equals(timbreStatus)) {
            status = "UPLOADING";
            return;
        }
        if ("ANALYZING".equals(rangeStatus) || "ANALYZING".equals(timbreStatus)) {
            status = "ANALYZING";
            return;
        }
        status = "CREATED";
    }
}
