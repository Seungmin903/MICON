package com.micon.backend.auth.application;

import com.micon.backend.auth.config.AuthProperties;
import com.micon.backend.auth.domain.RefreshToken;
import com.micon.backend.auth.domain.RefreshTokenRepository;
import com.micon.backend.auth.token.RefreshTokenHasher;
import com.micon.backend.auth.token.TokenPair;
import com.micon.backend.auth.token.TokenProvider;
import com.micon.backend.user.domain.User;
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
}
