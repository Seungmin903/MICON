package com.micon.backend.user;

import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserQueryApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @Test
    void returnsAuthenticatedUsersProfileWithoutSensitiveFields() throws Exception {
        User user = userRepository.save(new User(
                "profile@micon.com", passwordEncoder.encode("Password123!")));

        mockMvc.perform(get("/users/me").with(jwt().jwt(token -> token
                        .subject(user.getId().toString())
                        .claim("sid", "test-session"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.email").value("profile@micon.com"))
                .andExpect(jsonPath("$.data.authType").value("EMAIL"))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.withdrawnAt").doesNotExist());
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsWithdrawnAccount() throws Exception {
        User user = userRepository.saveAndFlush(new User(
                "withdrawn@micon.com", passwordEncoder.encode("Password123!")));
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                user.getId());
        entityManager.clear();

        mockMvc.perform(get("/users/me").with(jwt().jwt(token -> token
                        .subject(user.getId().toString())
                        .claim("sid", "test-session"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }
}
