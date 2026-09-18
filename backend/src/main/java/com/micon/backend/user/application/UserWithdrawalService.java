package com.micon.backend.user.application;

import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.AuthType;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final UserDataCleanupService dataCleanupService;

    public UserWithdrawalService(UserRepository userRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 PasswordEncoder passwordEncoder,
                                 Clock clock,
                                 UserDataCleanupService dataCleanupService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dataCleanupService = dataCleanupService;
    }

    @Transactional
    public void withdraw(long userId, String currentPassword) {
        User user = userRepository.findByIdForUpdate(userId)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));

        if (user.getAuthType() != AuthType.EMAIL
                || user.getPassword() == null
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }

        LocalDateTime withdrawnAt = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        dataCleanupService.deleteForWithdrawal(user.getId());
        user.withdraw(withdrawnAt);
        refreshTokenRepository.revokeAllByUserId(user.getId(), withdrawnAt);
    }
}
