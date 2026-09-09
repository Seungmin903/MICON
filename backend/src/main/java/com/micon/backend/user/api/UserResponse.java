package com.micon.backend.user.api;

import com.micon.backend.user.domain.AuthType;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserStatus;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId,
        String email,
        AuthType authType,
        boolean emailVerified,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getAuthType(),
                user.isEmailVerified(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }
}
