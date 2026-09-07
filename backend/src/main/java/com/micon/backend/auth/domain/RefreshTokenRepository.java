package com.micon.backend.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import com.micon.backend.user.domain.UserStatus;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findBySessionId(String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean existsBySessionIdAndUser_IdAndUser_StatusAndRevokedAtIsNullAndExpiresAtAfter(
            String sessionId, Long userId, UserStatus status, LocalDateTime now);
}
