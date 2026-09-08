package com.micon.backend.auth.api;

import com.micon.backend.auth.application.EmailAuthService;
import com.micon.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final EmailAuthService emailAuthService;

    public AuthController(EmailAuthService emailAuthService) {
        this.emailAuthService = emailAuthService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody AuthRequest.Login request) {
        return ResponseEntity.ok(ApiResponse.success(emailAuthService.login(request)));
    }
}
