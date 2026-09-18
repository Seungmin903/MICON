package com.micon.backend.domain.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AiServiceTokenValidator {
    private final byte[] expected;

    public AiServiceTokenValidator(@Value("${ai.service-token:}") String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    public void validate(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (expected.length == 0 || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.INVALID_AI_SERVICE_TOKEN);
        }
    }
}
