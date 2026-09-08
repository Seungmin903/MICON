package com.micon.backend.user.api;

import com.micon.backend.auth.api.AuthRequest;
import com.micon.backend.auth.api.AuthResponse;
import com.micon.backend.auth.application.EmailAuthService;
import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.application.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    public UserController(EmailAuthService emailAuthService, UserQueryService userQueryService) {
        this.emailAuthService = emailAuthService;
        this.userQueryService = userQueryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AuthResponse>> signUp(@Valid @RequestBody AuthRequest.SignUp request) {
        AuthResponse response = emailAuthService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(@AuthenticationPrincipal Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return ResponseEntity.ok(ApiResponse.success(userQueryService.getMe(userId)));
    }
}
