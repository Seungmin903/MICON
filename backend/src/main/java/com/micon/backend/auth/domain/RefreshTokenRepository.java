package com.micon.backend.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import com.micon.backend.user.domain.UserStatus;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findBySessionId(String sessionId);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean existsBySessionIdAndUser_IdAndUser_StatusAndRevokedAtIsNullAndExpiresAtAfter(
            String sessionId, Long userId, UserStatus status, LocalDateTime now);
}
