package com.micon.backend.auth;

import com.micon.backend.domain.auth.service.AuthSessionService;
import com.micon.backend.domain.auth.token.RefreshToken;
import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.auth.token.RefreshTokenHasher;
import com.micon.backend.domain.auth.token.TokenPair;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest
@Transactional
class AuthFoundationIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void createsPersistentSessionAndAuthenticatesAccessToken() {
        String encodedPassword = passwordEncoder.encode("Password123!");
        User user = userRepository.save(new User("member@micon.com", encodedPassword));

        TokenPair tokens = authSessionService.create(user, "android-device-id", "Galaxy");

        RefreshToken session = refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(tokens.refreshToken())).orElseThrow();
        Jwt jwt = jwtDecoder.decode(tokens.accessToken());

        assertThat(passwordEncoder.matches("Password123!", user.getPassword())).isTrue();
        assertThat(session.getUser().getId()).isEqualTo(user.getId());
        assertThat(session.getDeviceId()).isEqualTo("android-device-id");
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(session.getSessionId());
        assertThat(session.getTokenHash()).hasSize(64).doesNotContain(tokens.refreshToken());
    }

    @Test
    void rejectsAccessTokenAfterSessionIsRevoked() {
        User user = userRepository.save(new User(
                "revoked@micon.com", passwordEncoder.encode("Password123!")));
        TokenPair tokens = authSessionService.create(user, null, null);
        RefreshToken session = refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(tokens.refreshToken())).orElseThrow();
        session.revoke(java.time.LocalDateTime.now());
        refreshTokenRepository.flush();

        assertThatExceptionOfType(JwtValidationException.class)
                .isThrownBy(() -> jwtDecoder.decode(tokens.accessToken()));
    }
}
