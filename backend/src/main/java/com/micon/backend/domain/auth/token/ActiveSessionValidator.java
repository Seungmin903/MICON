package com.micon.backend.domain.auth.token;

import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.user.domain.UserStatus;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class ActiveSessionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_SESSION = new OAuth2Error(
            "invalid_token", "The authentication session is not active", null);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public ActiveSessionValidator(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sessionId = jwt.getClaimAsString("sid");
        if (sessionId == null) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }

        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }

        boolean active = refreshTokenRepository
                .existsBySessionIdAndUser_IdAndUser_StatusAndRevokedAtIsNullAndExpiresAtAfter(
                        sessionId,
                        userId,
                        UserStatus.ACTIVE,
                        LocalDateTime.ofInstant(clock.instant(), clock.getZone())
                );
        return active
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_SESSION);
    }
}
