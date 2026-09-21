package com.micon.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequest {

    private AuthRequest() {
    }

    public record SignUp(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "올바른 이메일 형식이 아닙니다.")
            @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
            String email,

            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
            @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
            String password,

            @Size(max = 100, message = "기기 식별자는 100자 이하여야 합니다.")
            String deviceId,

            @Size(max = 100, message = "기기 이름은 100자 이하여야 합니다.")
            String deviceName
    ) {
        public SignUp {
            email = trim(email);
        }
    }

    public record Login(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "올바른 이메일 형식이 아닙니다.")
            @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
            String email,

            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(max = 64, message = "비밀번호는 64자 이하여야 합니다.")
            String password,

            @Size(max = 100, message = "기기 식별자는 100자 이하여야 합니다.")
            String deviceId,

            @Size(max = 100, message = "기기 이름은 100자 이하여야 합니다.")
            String deviceName
    ) {
        public Login {
            email = trim(email);
        }
    }

    public record GoogleLogin(
            @NotBlank(message = "구글 인증 정보가 필요합니다.")
            @Size(max = 8192, message = "구글 인증 정보가 너무 깁니다.")
            String idToken,

            @Size(max = 100, message = "기기 식별자는 100자 이하여야 합니다.")
            String deviceId,

            @Size(max = 100, message = "기기 이름은 100자 이하여야 합니다.")
            String deviceName
    ) {
        public GoogleLogin {
            idToken = trim(idToken);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
