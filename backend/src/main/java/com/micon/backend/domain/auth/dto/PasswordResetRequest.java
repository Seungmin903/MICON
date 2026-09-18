package com.micon.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PasswordResetRequest {
    private PasswordResetRequest() {
    }

    public record Issue(
            @NotBlank @Email @Size(max = 255) String email
    ) {
        public Issue {
            email = email == null ? null : email.trim();
        }
    }

    public record Verify(
            @NotBlank @Size(max = 36) String requestId,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 6자리 숫자여야 합니다.") String code
    ) {
    }

    public record Reset(
            @NotBlank @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
            String newPassword
    ) {
    }
}
