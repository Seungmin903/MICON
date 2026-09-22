package com.micon.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.auth.token.RefreshToken;
import com.micon.backend.domain.auth.token.RefreshTokenHasher;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.repository.UserRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserPasswordApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RefreshTokenHasher refreshTokenHasher;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void incorrectCurrentPasswordKeepsTheAuthenticatedSession() throws Exception {
        userRepository.save(new User("password-keep@micon.com", passwordEncoder.encode("Password123!")));
        SessionTokens session = loginAndRead("password-keep@micon.com", "Password123!");

        mockMvc.perform(patch("/users/me/password")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"WrongPassword!\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        RefreshToken token = refreshTokenRepository.findByTokenHash(
                refreshTokenHasher.hash(session.refreshToken())).orElseThrow();
        assertThat(token.getRevokedAt()).isNull();
        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }

    private SessionTokens loginAndRead(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
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
