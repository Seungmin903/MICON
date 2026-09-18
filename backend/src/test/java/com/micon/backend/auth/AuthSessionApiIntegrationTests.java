package com.micon.backend.auth;

import com.micon.backend.domain.auth.token.RefreshToken;
import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.auth.token.RefreshTokenHasher;
import com.micon.backend.domain.user.domain.User;
import com.micon.backend.domain.user.domain.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthSessionApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RefreshTokenHasher refreshTokenHasher;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void rotatesRefreshTokenAndRejectsReuse() throws Exception {
        SessionTokens initial = login();

        MvcResult refreshed = mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + initial.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        String rotatedRefreshToken = field(refreshed, "refreshToken");
        assertThat(rotatedRefreshToken).isNotEqualTo(initial.refreshToken());
        assertThat(refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(initial.refreshToken()))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(rotatedRefreshToken))).isPresent();

        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + initial.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesCurrentSessionAndBlocksAccessToken() throws Exception {
        SessionTokens tokens = login();

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        RefreshToken session = refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(tokens.refreshToken())).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + tokens.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void rejectsMissingRefreshToken() throws Exception {
        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    private SessionTokens login() throws Exception {
        userRepository.save(new User("session@micon.com", passwordEncoder.encode("Password123!")));
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"session@micon.com","password":"Password123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return new SessionTokens(field(result, "accessToken"), field(result, "refreshToken"));
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
