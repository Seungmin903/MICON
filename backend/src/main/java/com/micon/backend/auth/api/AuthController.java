package com.micon.backend.auth.api;

import com.micon.backend.auth.application.EmailAuthService;
import com.micon.backend.auth.application.AuthSessionService;
import com.micon.backend.auth.application.PasswordResetService;
import com.micon.backend.auth.token.TokenPair;
import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final EmailAuthService emailAuthService;
    private final AuthSessionService authSessionService;
    private final PasswordResetService passwordResetService;

    public AuthController(EmailAuthService emailAuthService, AuthSessionService authSessionService,
                          PasswordResetService passwordResetService) {
        this.emailAuthService = emailAuthService;
        this.authSessionService = authSessionService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody AuthRequest.Login request) {
        return ResponseEntity.ok(ApiResponse.success(emailAuthService.login(request)));
    }

    @PostMapping("/tokens/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String refreshToken = bearerToken(authorization);
        TokenPair tokens = authSessionService.refresh(refreshToken);
        return ResponseEntity.ok(ApiResponse.success(TokenRefreshResponse.from(tokens)));
    }

    @DeleteMapping("/sessions/current")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal Jwt jwt) {
        authSessionService.logout(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("sid"));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/password-reset-requests")
    public ResponseEntity<ApiResponse<PasswordResetResponse.Issued>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest.Issue request) {
        return ResponseEntity.ok(ApiResponse.success(passwordResetService.issue(request.email())));
    }

    @PostMapping("/password-reset-verifications")
    public ResponseEntity<ApiResponse<PasswordResetResponse.Verified>> verifyPasswordReset(
            @Valid @RequestBody PasswordResetRequest.Verify request) {
        return ResponseEntity.ok(ApiResponse.success(
                passwordResetService.verify(request.requestId(), request.code())));
    }

    @PostMapping("/password-resets")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @RequestHeader(value = "X-Password-Reset-Token", required = false) String resetToken,
            @Valid @RequestBody PasswordResetRequest.Reset request) {
        if (resetToken == null || resetToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }
        passwordResetService.reset(resetToken, request.newPassword());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return authorization.substring(7).trim();
    }
}
