package com.micon.backend.domain.auth.service;

import com.micon.backend.domain.auth.dto.AuthRequest;
import com.micon.backend.domain.auth.dto.AuthResponse;
import com.micon.backend.domain.auth.token.TokenPair;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.AuthType;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class EmailAuthService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final Clock clock;

    public EmailAuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                            AuthSessionService authSessionService, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse signUp(AuthRequest.SignUp request) {
        validatePasswordBytes(request.password());
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = new User(email, passwordEncoder.encode(request.password()));
        try {
            user.markLoggedIn(LocalDateTime.ofInstant(clock.instant(), clock.getZone()));
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        TokenPair tokens = authSessionService.create(user, request.deviceId(), request.deviceName());
        return AuthResponse.of(user.getId(), user.getEmail(), tokens);
    }

    @Transactional
    public AuthResponse login(AuthRequest.Login request) {
        validatePasswordBytes(request.password());
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (user.getAuthType() != AuthType.EMAIL
                || user.getPassword() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        user.markLoggedIn(LocalDateTime.ofInstant(clock.instant(), clock.getZone()));
        TokenPair tokens = authSessionService.create(user, request.deviceId(), request.deviceName());
        return AuthResponse.of(user.getId(), user.getEmail(), tokens);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호가 너무 깁니다.");
        }
    }
}
