package com.micon.backend.user.api;

import com.micon.backend.auth.api.AuthRequest;
import com.micon.backend.auth.api.AuthResponse;
import com.micon.backend.auth.application.EmailAuthService;
import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.application.UserQueryService;
import com.micon.backend.user.application.UserWithdrawalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final EmailAuthService emailAuthService;
    private final UserQueryService userQueryService;
    private final UserWithdrawalService userWithdrawalService;

    public UserController(EmailAuthService emailAuthService, UserQueryService userQueryService,
                          UserWithdrawalService userWithdrawalService) {
        this.emailAuthService = emailAuthService;
        this.userQueryService = userQueryService;
        this.userWithdrawalService = userWithdrawalService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AuthResponse>> signUp(@Valid @RequestBody AuthRequest.SignUp request) {
        AuthResponse response = emailAuthService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.success(userQueryService.getMe(userId(jwt))));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserRequest.Withdraw request) {
        userWithdrawalService.withdraw(userId(jwt), request.currentPassword());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
