package com.micon.backend.domain.auth.repository;

import com.micon.backend.domain.auth.entity.PasswordResetAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface PasswordResetAttemptRepository extends JpaRepository<PasswordResetAttempt, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetAttempt> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetAttempt> findByResetTokenHash(String resetTokenHash);
}
