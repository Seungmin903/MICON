package com.micon.backend.domain.user.dto;

import com.micon.backend.domain.user.entity.AuthType;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.entity.UserStatus;

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
