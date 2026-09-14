package com.micon.backend.voice.domain;

import com.micon.backend.user.domain.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_voice_profile")
public class UserVoiceProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id") private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true) private User user;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, unique = true) private VoiceAnalysisSession session;
    @Column(name = "profile_version", nullable = false) private long profileVersion;
    @Column(name = "saved_at", nullable = false) private LocalDateTime savedAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    protected UserVoiceProfile() {}
    public UserVoiceProfile(User user, VoiceAnalysisSession session, long profileVersion) {
        if (user == null || session == null || profileVersion < 1) throw new IllegalArgumentException("invalid profile");
        this.user = user; this.session = session; this.profileVersion = profileVersion;
        this.savedAt = LocalDateTime.now(); this.updatedAt = this.savedAt;
    }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public VoiceAnalysisSession getSession() { return session; }
    public long getProfileVersion() { return profileVersion; }

    public void replaceWith(VoiceAnalysisSession session) {
        if (session == null || !session.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("profile session owner does not match");
        }
        this.session = session;
        this.profileVersion++;
        this.updatedAt = LocalDateTime.now();
    }
}
