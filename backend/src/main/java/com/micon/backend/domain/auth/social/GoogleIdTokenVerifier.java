package com.micon.backend.domain.auth.social;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.auth.config.GoogleAuthProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Google 공개 JWK로 서명, 만료, 발급자, audience, 검증된 이메일을 확인한다. */
@Component
public class GoogleIdTokenVerifier {

    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final GoogleAuthProperties properties;
    private final NimbusJwtDecoder decoder;

    public GoogleIdTokenVerifier(GoogleAuthProperties properties) {
        this.properties = properties;
        this.decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), this::validateGoogleClaims
        ));
    }

    public GoogleIdentity verify(String idToken) {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.GOOGLE_LOGIN_NOT_CONFIGURED);
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"));
        } catch (JwtException exception) {
            throw new BusinessException(ErrorCode.INVALID_GOOGLE_ID_TOKEN);
        }
    }

    private OAuth2TokenValidatorResult validateGoogleClaims(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
        if (!GOOGLE_ISSUERS.contains(issuer)
                || !jwt.getAudience().contains(properties.webClientId())
                || !Boolean.TRUE.equals(jwt.getClaim("email_verified"))
                || isBlank(jwt.getSubject())
                || isBlank(jwt.getClaimAsString("email"))) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_google_id_token"));
        }
        return OAuth2TokenValidatorResult.success();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record GoogleIdentity(String subject, String email) {
    }
}
