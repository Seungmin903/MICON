package com.micon.backend.auth.mail;

import com.micon.backend.auth.config.PasswordResetProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpPasswordResetCodeSender implements PasswordResetCodeSender {

    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;

    public SmtpPasswordResetCodeSender(JavaMailSender mailSender, PasswordResetProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("[MICON] 비밀번호 재설정 인증 코드");
        message.setText("비밀번호 재설정 인증 코드는 " + code + "입니다. 제한 시간 안에 입력해 주세요.");
        mailSender.send(message);
    }
}
