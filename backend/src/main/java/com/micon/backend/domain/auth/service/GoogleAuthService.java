package com.micon.backend.domain.auth.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.auth.dto.AuthRequest;
import com.micon.backend.domain.auth.dto.AuthResponse;
import com.micon.backend.domain.auth.social.GoogleIdTokenVerifier;
import com.micon.backend.domain.auth.social.SocialIdentity;
import com.micon.backend.domain.auth.social.SocialIdentityRepository;
import com.micon.backend.domain.auth.token.TokenPair;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class GoogleAuthService {

    private static final String PROVIDER = "GOOGLE";

    private final GoogleIdTokenVerifier tokenVerifier;
    private final SocialIdentityRepository socialIdentityRepository;
    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;
    private final Clock clock;

    public GoogleAuthService(GoogleIdTokenVerifier tokenVerifier, SocialIdentityRepository socialIdentityRepository,
                             UserRepository userRepository, AuthSessionService authSessionService, Clock clock) {
        this.tokenVerifier = tokenVerifier;
        this.socialIdentityRepository = socialIdentityRepository;
        this.userRepository = userRepository;
        this.authSessionService = authSessionService;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse login(AuthRequest.GoogleLogin request) {
        GoogleIdTokenVerifier.GoogleIdentity identity = tokenVerifier.verify(request.idToken());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        User user = socialIdentityRepository.findByProviderAndProviderSubject(PROVIDER, identity.subject())
                .map(SocialIdentity::getUser)
                .orElseGet(() -> linkIdentity(identity, now));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        user.markLoggedIn(now);
        TokenPair tokens = authSessionService.create(user, request.deviceId(), request.deviceName());
        return AuthResponse.of(user.getId(), user.getEmail(), tokens);
    }

    private User linkIdentity(GoogleIdTokenVerifier.GoogleIdentity identity, LocalDateTime now) {
        String email = identity.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(User.social(email)));
        socialIdentityRepository.save(new SocialIdentity(user, PROVIDER, identity.subject(), now));
        return user;
    }
}
