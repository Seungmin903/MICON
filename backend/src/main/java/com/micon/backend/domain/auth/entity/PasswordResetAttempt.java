package com.micon.backend.domain.auth.entity;

import com.micon.backend.domain.user.entity.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "password_reset_request")
public class PasswordResetAttempt {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "password_reset_request_id")
        private Long id;

        @Column(name = "request_id",
                nullable = false,
                unique = true,
                length = 36
        )
        private String requestId;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "user_id", nullable = false)
        private User user;

        @Column(name = "code_hash", nullable = false, length = 255)
        private String codeHash;

        @Column(name = "attempt_count", nullable = false)
        private int attemptCount;

        @Column(name = "expires_at", nullable = false)
        private LocalDateTime expiresAt;

        @Column(name = "verified_at")
        private LocalDateTime verifiedAt;

        @Column(name = "reset_token_hash", unique = true, length = 64)
        private String resetTokenHash;

        @Column(name = "reset_token_expires_at")
        private LocalDateTime resetTokenExpiresAt;

        @Column(name = "used_at")
        private LocalDateTime usedAt;

        @Column(name = "created_at", nullable = false, updatable = false)
        private LocalDateTime createdAt;

        protected PasswordResetAttempt() {
        }

        public PasswordResetAttempt(String requestId, User user, String codeHash,
                                    LocalDateTime expiresAt, LocalDateTime createdAt) {
            this.requestId = requestId;
            this.user = user;
            this.codeHash = codeHash;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
        }

        public String getRequestId() { return requestId; }
        public User getUser() { return user; }
        public String getCodeHash() { return codeHash; }
        public int getAttemptCount() { return attemptCount; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public LocalDateTime getUsedAt() { return usedAt; }

        public boolean canVerifyAt(LocalDateTime now, int maxAttempts) {
            return usedAt == null && verifiedAt == null && expiresAt.isAfter(now) && attemptCount < maxAttempts;
        }

        public void recordFailedAttempt() { attemptCount++; }

        public void verify(String resetTokenHash, LocalDateTime verifiedAt, LocalDateTime tokenExpiresAt) {
            this.verifiedAt = verifiedAt;
            this.resetTokenHash = resetTokenHash;
            this.resetTokenExpiresAt = tokenExpiresAt;
        }

        public boolean canResetAt(LocalDateTime now) {
            return usedAt == null && verifiedAt != null && resetTokenExpiresAt != null
                    && resetTokenExpiresAt.isAfter(now);
        }

        public void use(LocalDateTime usedAt) { this.usedAt = usedAt; }
}
