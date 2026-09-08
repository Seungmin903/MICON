package com.micon.backend.user.api;

import com.micon.backend.auth.api.AuthRequest;
import com.micon.backend.auth.api.AuthResponse;
import com.micon.backend.auth.application.EmailAuthService;
import com.micon.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final EmailAuthService emailAuthService;

    public UserController(EmailAuthService emailAuthService) {
        this.emailAuthService = emailAuthService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AuthResponse>> signUp(@Valid @RequestBody AuthRequest.SignUp request) {
        AuthResponse response = emailAuthService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
