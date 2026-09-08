package com.micon.backend.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndStatus(Long id, UserStatus status);

    boolean existsByEmail(String email);
}
