package com.micon.backend.auth;

import com.micon.backend.domain.user.repository.UserRepository;

import com.micon.backend.domain.auth.repository.RefreshTokenRepository;
import com.micon.backend.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmailAuthApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void signsUpAndCreatesLoggedInSession() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "  NewUser@Micon.COM ",
                                  "password": "Password123!",
                                  "deviceId": "android-id",
                                  "deviceName": "Galaxy"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        User user = userRepository.findByEmail("newuser@micon.com").orElseThrow();
        assertThat(user.getPassword()).isNotEqualTo("Password123!");
        assertThat(passwordEncoder.matches("Password123!", user.getPassword())).isTrue();
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void logsInWithNormalizedEmailAndUpdatesLoginTime() throws Exception {
        User user = userRepository.save(new User(
                "member@micon.com", passwordEncoder.encode("Password123!")));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "MEMBER@MICON.COM",
                                  "password": "Password123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        userRepository.save(new User(
                "duplicate@micon.com", passwordEncoder.encode("Password123!")));

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "DUPLICATE@micon.com",
                                  "password": "Password123!"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsWrongPasswordWithoutRevealingCredential() throws Exception {
        userRepository.save(new User(
                "login@micon.com", passwordEncoder.encode("Password123!")));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "login@micon.com",
                                  "password": "WrongPassword!"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    void rejectsInvalidSignUpRequest() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "password": "short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsSignUpPasswordsThatDoNotMeetThePasswordPolicy() throws Exception {
        String[] invalidPasswords = {
                "12345678!",      // 영문 없음
                "Password!",      // 숫자 없음
                "Password123",    // 허용된 특수문자 없음
                "Password 123!",  // 공백 포함
                "Password123_"    // 허용되지 않은 특수문자
        };

        for (int index = 0; index < invalidPasswords.length; index++) {
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "invalid-password-%d@micon.com",
                                      "password": "%s"
                                    }
                                    """.formatted(index, invalidPasswords[index])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void protectsMemberOnlyEndpoint() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
