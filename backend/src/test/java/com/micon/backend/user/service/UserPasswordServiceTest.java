package com.micon.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.user.service.UserPasswordService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserPasswordServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private UserPasswordService service;
    private final User user = new User("user@micon.test", "old-hash");

    @BeforeEach
    void setUp() {
        service = new UserPasswordService(userRepository, refreshTokenRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void changesPasswordAndRevokesAllSessionsAfterCurrentPasswordVerification() {
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("new-password!1", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("new-password!1")).thenReturn("new-hash");

        service.change(1L, "current-password", "new-password!1");

        verify(passwordEncoder).encode("new-password!1");
        verify(refreshTokenRepository).revokeAllByUserId(any(), any());
    }

    @Test
    void rejectsIncorrectCurrentPasswordWithoutChangingOrRevoking() {
        when(passwordEncoder.matches("wrong-password", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.change(1L, "wrong-password", "new-password!1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);
                    assertThat(error.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        verify(passwordEncoder, never()).encode(any());
        verify(refreshTokenRepository, never()).revokeAllByUserId(any(), any());
    }

    @Test
    void rejectsAReplacementThatMatchesTheCurrentPassword() {
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.change(1L, "current-password", "current-password"))
                .isInstanceOf(BusinessException.class);

        verify(passwordEncoder, never()).encode(any());
        verify(refreshTokenRepository, never()).revokeAllByUserId(any(), any());
    }
}
