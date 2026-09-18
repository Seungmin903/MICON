package com.micon.backend.domain.user.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.user.application.UserWithdrawalService;
import com.micon.backend.domain.user.application.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    private final UserQueryService userQueryService;
    private final UserWithdrawalService userWithdrawalService;

    public UserController(UserQueryService userQueryService,
                          UserWithdrawalService userWithdrawalService) {
        this.userQueryService = userQueryService;
        this.userWithdrawalService = userWithdrawalService;
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
