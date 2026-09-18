package com.micon.backend.domain.auth.mail;

public interface PasswordResetCodeSender {
    void send(String email, String code);
}
