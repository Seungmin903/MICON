package com.micon.backend.domain.auth.social;

import com.micon.backend.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "social_identity", uniqueConstraints = @UniqueConstraint(
        name = "uk_social_identity_provider_subject",
        columnNames = {"provider", "provider_subject"}
))
public class SocialIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "social_identity_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SocialIdentity() {
    }

    public SocialIdentity(User user, String provider, String providerSubject, LocalDateTime createdAt) {
        this.user = user;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.createdAt = createdAt;
    }

    public User getUser() {
        return user;
    }
}
