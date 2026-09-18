package com.micon.backend.domain.auth.service;

import com.micon.backend.domain.auth.dto.PasswordResetResponse;
import com.micon.backend.domain.auth.entity.PasswordResetAttempt;
import com.micon.backend.domain.auth.config.PasswordResetProperties;
import com.micon.backend.domain.auth.repository.PasswordResetAttemptRepository;
import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.auth.mail.PasswordResetCodeSender;
import com.micon.backend.domain.auth.token.RefreshTokenHasher;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.AuthType;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class PasswordResetService {
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordResetAttemptRepository requestRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetCodeSender codeSender;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenHasher tokenHasher;
    private final PasswordResetProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetAttemptRepository requestRepository,
                                RefreshTokenRepository refreshTokenRepository,
                                PasswordResetCodeSender codeSender,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenHasher tokenHasher,
                                PasswordResetProperties properties,
                                Clock clock) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.codeSender = codeSender;
        this.passwordEncoder = passwordEncoder;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PasswordResetResponse.Issued issue(String rawEmail) {
        String requestId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(properties.codeTtl());
        User user = userRepository.findByEmail(rawEmail.trim().toLowerCase(Locale.ROOT))
                .filter(found -> found.getAuthType() == AuthType.EMAIL)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElse(null);

        if (user == null) {
            return new PasswordResetResponse.Issued(requestId, expiresAt);
        }

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        LocalDateTime now = now();
        requestRepository.save(new PasswordResetAttempt(
                requestId, user, passwordEncoder.encode(code),
                LocalDateTime.ofInstant(expiresAt, clock.getZone()), now));
        try {
            codeSender.send(user.getEmail(), code);
        } catch (MailException exception) {
            throw new BusinessException(ErrorCode.EMAIL_DELIVERY_FAILED);
        }
        return new PasswordResetResponse.Issued(requestId, expiresAt);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public PasswordResetResponse.Verified verify(String requestId, String code) {
        LocalDateTime now = now();
        PasswordResetAttempt request = requestRepository.findByRequestId(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_RESET_CODE));
        if (!request.canVerifyAt(now, properties.maxAttempts())) {
            throw new BusinessException(ErrorCode.INVALID_RESET_CODE);
        }
        if (!passwordEncoder.matches(code, request.getCodeHash())) {
            request.recordFailedAttempt();
            throw new BusinessException(ErrorCode.INVALID_RESET_CODE);
        }

        String resetToken = randomToken();
        Instant expiresAt = clock.instant().plus(properties.tokenTtl());
        request.verify(tokenHasher.hash(resetToken), now,
                LocalDateTime.ofInstant(expiresAt, clock.getZone()));
        return new PasswordResetResponse.Verified(resetToken, expiresAt);
    }

    @Transactional
    public void reset(String rawResetToken, String newPassword) {
        validatePasswordBytes(newPassword);
        LocalDateTime now = now();
        PasswordResetAttempt request = requestRepository
                .findByResetTokenHash(tokenHasher.hash(rawResetToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN));
        if (!request.canResetAt(now) || request.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }

        request.getUser().changePassword(passwordEncoder.encode(newPassword), now);
        request.use(now);
        refreshTokenRepository.revokeAllByUserId(request.getUser().getId(), now);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호가 너무 깁니다.");
        }
    }
}
