package com.micon.backend.domain.auth.service;

import com.micon.backend.domain.auth.config.AuthProperties;
import com.micon.backend.domain.auth.token.RefreshToken;
import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.auth.token.RefreshTokenHasher;
import com.micon.backend.domain.auth.token.TokenPair;
import com.micon.backend.domain.auth.token.TokenProvider;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final TokenProvider tokenProvider;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthProperties authProperties;
    private final Clock clock;

    public AuthSessionService(TokenProvider tokenProvider, RefreshTokenHasher refreshTokenHasher,
                              RefreshTokenRepository refreshTokenRepository, AuthProperties authProperties,
                              Clock clock) {
        this.tokenProvider = tokenProvider;
        this.refreshTokenHasher = refreshTokenHasher;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    @Transactional
    public TokenPair create(User user, String deviceId, String deviceName) {
        String sessionId = UUID.randomUUID().toString();
        TokenPair tokens = tokenProvider.issue(user.getId(), sessionId);
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.ofInstant(
                clock.instant().plus(authProperties.refreshTokenTtl()), clock.getZone());
        RefreshToken session = new RefreshToken(
                user,
                sessionId,
                refreshTokenHasher.hash(tokens.refreshToken()),
                deviceId,
                deviceName,
                refreshTokenExpiresAt
        );
        refreshTokenRepository.save(session);
        return tokens;
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        LocalDateTime now = now();
        RefreshToken session = refreshTokenRepository.findByTokenHash(refreshTokenHasher.hash(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (!session.isActiveAt(now) || session.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        TokenPair tokens = tokenProvider.issue(session.getUser().getId(), session.getSessionId());
        session.rotate(
                refreshTokenHasher.hash(tokens.refreshToken()),
                now.plus(authProperties.refreshTokenTtl()),
                now
        );
        return tokens;
    }

    @Transactional
    public void logout(long userId, String sessionId) {
        RefreshToken session = refreshTokenRepository.findBySessionId(sessionId)
                .filter(token -> token.getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (session.getRevokedAt() == null) {
            session.revoke(now());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }
}
