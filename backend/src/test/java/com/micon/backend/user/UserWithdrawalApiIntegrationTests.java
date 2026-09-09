package com.micon.backend.user;

import com.micon.backend.auth.domain.RefreshToken;
import com.micon.backend.auth.domain.RefreshTokenRepository;
import com.micon.backend.auth.token.RefreshTokenHasher;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import com.micon.backend.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserWithdrawalApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RefreshTokenHasher refreshTokenHasher;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void withdrawsAccountAndRevokesEverySession() throws Exception {
        User user = userRepository.save(new User(
                "withdraw@micon.com", passwordEncoder.encode("Password123!")));
        SessionTokens first = loginAndRead("withdraw@micon.com", "Password123!");
        SessionTokens second = loginAndRead("withdraw@micon.com", "Password123!");

        mockMvc.perform(delete("/users/me")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        User withdrawn = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(withdrawn.getWithdrawnAt()).isNotNull();
        assertRevoked(first.refreshToken());
        assertRevoked(second.refreshToken());

        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isUnauthorized());
        performLogin("withdraw@micon.com", "Password123!")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        mockMvc.perform(post("/auth/tokens/refresh")
                        .header("Authorization", "Bearer " + second.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void failedReauthenticationLeavesAccountAndSessionUnchanged() throws Exception {
        User user = userRepository.save(new User(
                "keep@micon.com", passwordEncoder.encode("Password123!")));
        SessionTokens session = loginAndRead("keep@micon.com", "Password123!");

        mockMvc.perform(delete("/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"WrongPassword!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        User active = userRepository.findById(user.getId()).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(active.getWithdrawnAt()).isNull();
        RefreshToken token = refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(session.refreshToken())).orElseThrow();
        assertThat(token.getRevokedAt()).isNull();
        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsWithdrawalWithoutCurrentPassword() throws Exception {
        userRepository.save(new User("validation@micon.com", passwordEncoder.encode("Password123!")));
        SessionTokens session = loginAndRead("validation@micon.com", "Password123!");

        mockMvc.perform(delete("/users/me")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private org.springframework.test.web.servlet.ResultActions performLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private SessionTokens loginAndRead(String email, String password) throws Exception {
        MvcResult result = performLogin(email, password).andExpect(status().isOk()).andReturn();
        return new SessionTokens(field(result, "accessToken"), field(result, "refreshToken"));
    }

    private void assertRevoked(String refreshToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(refreshToken)).orElseThrow();
        assertThat(token.getRevokedAt()).isNotNull();
    }

    private String field(MvcResult result, String name) throws Exception {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private record SessionTokens(String accessToken, String refreshToken) {
    }
}
