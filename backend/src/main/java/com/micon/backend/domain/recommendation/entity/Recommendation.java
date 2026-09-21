package com.micon.backend.domain.recommendation.entity;

import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
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
import java.util.Objects;

@Entity
@Table(name = "recommendation")
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommendation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserVoiceProfile voiceProfile;

    // Immutable input identity: the profile row itself is replaced on remeasurement.
    // NULL marks pre-migration results whose source identity cannot be established.
    @Column(name = "source_session_id")
    private Long sourceSessionId;

    @Column(name = "profile_version")
    private Long profileVersion;

    @Column(name = "preference_version")
    private Long preferenceVersion;

    @Column(name = "job_id", unique = true, length = 255)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecommendationMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecommendationStatus status;

    @Column(nullable = false)
    private short progress;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "algorithm_version", length = 50)
    private String algorithmVersion;

    @Column(name = "scoring_version", length = 50)
    private String scoringVersion;

    @Column(name = "composition_version", length = 50)
    private String compositionVersion;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    protected Recommendation() {
    }

    public Recommendation(User user, UserVoiceProfile voiceProfile, RecommendationMode mode) {
        this(user, voiceProfile, mode, 0L);
    }

    public Recommendation(User user, UserVoiceProfile voiceProfile, RecommendationMode mode, long preferenceVersion) {
        if (user == null || voiceProfile == null) {
            throw new IllegalArgumentException("user and voiceProfile are required");
        }
        if (!sameUser(user, voiceProfile.getUser())) {
            throw new IllegalArgumentException("voiceProfile must belong to user");
        }
        this.user = user;
        this.voiceProfile = voiceProfile;
        this.sourceSessionId = voiceProfile.getSession().getId();
        this.profileVersion = voiceProfile.getProfileVersion();
        this.preferenceVersion = preferenceVersion;
        this.mode = mode == null ? RecommendationMode.OVERALL : mode;
        this.status = RecommendationStatus.GENERATING;
        this.progress = 0;
        this.generatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public UserVoiceProfile getVoiceProfile() { return voiceProfile; }
    public Long getSourceSessionId() { return sourceSessionId; }
    public Long getProfileVersion() { return profileVersion; }
    public Long getPreferenceVersion() { return preferenceVersion; }
    public boolean isForCurrentProfile() {
        return profileVersion != null && sourceSessionId != null
                && profileVersion == voiceProfile.getProfileVersion()
                && Objects.equals(sourceSessionId, voiceProfile.getSession().getId());
    }
    public String getJobId() { return jobId; }
    public RecommendationMode getMode() { return mode; }
    public RecommendationStatus getStatus() { return status; }
    public short getProgress() { return progress; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getScoringVersion() { return scoringVersion; }
    public String getCompositionVersion() { return compositionVersion; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void assignJob(String jobId) {
        requireGenerating();
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        this.jobId = jobId;
    }

    public void updateProgress(int progress) {
        requireGenerating();
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        if (progress > this.progress) {
            this.progress = (short) progress;
        }
    }

    public void complete(String algorithmVersion, String scoringVersion, String compositionVersion,
                         LocalDateTime completedAt, LocalDateTime expiresAt) {
        requireGenerating();
        this.status = RecommendationStatus.COMPLETED;
        this.progress = 100;
        this.algorithmVersion = algorithmVersion;
        this.scoringVersion = scoringVersion;
        this.compositionVersion = compositionVersion;
        this.completedAt = requiredTime(completedAt);
        this.expiresAt = expiresAt;
    }

    public void completeEmpty(String algorithmVersion, String scoringVersion, String compositionVersion,
                              LocalDateTime completedAt, LocalDateTime expiresAt) {
        requireGenerating();
        this.status = RecommendationStatus.EMPTY;
        this.progress = 100;
        this.algorithmVersion = algorithmVersion;
        this.scoringVersion = scoringVersion;
        this.compositionVersion = compositionVersion;
        this.completedAt = requiredTime(completedAt);
        this.expiresAt = expiresAt;
    }

    public void fail(String failureCode, String failureReason, LocalDateTime completedAt) {
        requireGenerating();
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
        this.status = RecommendationStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.completedAt = requiredTime(completedAt);
    }

    private void requireGenerating() {
        if (status != RecommendationStatus.GENERATING) {
            throw new IllegalStateException("recommendation is already terminal: " + status);
        }
    }

    private LocalDateTime requiredTime(LocalDateTime value) {
        if (value == null) {
            throw new IllegalArgumentException("completedAt is required");
        }
        return value;
    }

    private boolean sameUser(User expected, User actual) {
        if (expected == actual) {
            return true;
        }
        return expected.getId() != null && Objects.equals(expected.getId(), actual.getId());
    }
}
