package com.micon.backend.domain.user.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.user.entity.AuthType;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated email-account password change. Successful changes invalidate every existing session. */
@Service
public class UserPasswordService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserPasswordService(UserRepository userRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               PasswordEncoder passwordEncoder,
                               Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public void change(long userId, String currentPassword, String newPassword) {
        validatePasswordBytes(newPassword);
        User user = userRepository.findByIdForUpdate(userId)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
        if (user.getAuthType() != AuthType.EMAIL || user.getPassword() == null
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_UNCHANGED);
        }

        LocalDateTime changedAt = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        user.changePassword(passwordEncoder.encode(newPassword), changedAt);
        refreshTokenRepository.revokeAllByUserId(user.getId(), changedAt);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호가 너무 깁니다.");
        }
    }
}
