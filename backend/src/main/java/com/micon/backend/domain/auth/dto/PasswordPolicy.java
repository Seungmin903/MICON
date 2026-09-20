package com.micon.backend.domain.auth.dto;

/** Shared password contract for sign-up and password reset. */
public final class PasswordPolicy {

    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,64}$";
    public static final String MESSAGE =
            "비밀번호는 공백 없이 영문, 숫자, 특수문자(@$!%*#?&)를 각각 하나 이상 포함한 8~64자여야 합니다.";

    private PasswordPolicy() {
    }
}
