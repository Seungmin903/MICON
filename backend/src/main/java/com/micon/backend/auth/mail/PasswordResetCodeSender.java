package com.micon.backend.auth.mail;

public interface PasswordResetCodeSender {
    void send(String email, String code);
}
