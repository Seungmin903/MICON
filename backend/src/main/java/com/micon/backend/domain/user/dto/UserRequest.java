package com.micon.backend.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.micon.backend.domain.auth.dto.PasswordPolicy;

public final class UserRequest {
    private UserRequest() {
    }

    public record Withdraw(
            @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
            @Size(max = 64, message = "비밀번호는 64자 이하여야 합니다.")
            String currentPassword
    ) {
    }

    public record ChangePassword(
            @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
            @Size(max = 64, message = "비밀번호는 64자 이하여야 합니다.")
            String currentPassword,

            @NotBlank(message = "새 비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
            @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
            String newPassword
    ) {
    }
}
