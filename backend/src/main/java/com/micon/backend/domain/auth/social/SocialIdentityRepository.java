package com.micon.backend.domain.auth.social;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SocialIdentityRepository extends JpaRepository<SocialIdentity, Long> {
    Optional<SocialIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);
}
