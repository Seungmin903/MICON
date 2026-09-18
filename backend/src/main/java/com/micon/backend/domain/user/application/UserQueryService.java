package com.micon.backend.domain.user.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.user.api.UserResponse;
import com.micon.backend.domain.user.domain.User;
import com.micon.backend.domain.user.domain.UserRepository;
import com.micon.backend.domain.user.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(long userId) {
        User user = userRepository.findByIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
        return UserResponse.from(user);
    }
}
