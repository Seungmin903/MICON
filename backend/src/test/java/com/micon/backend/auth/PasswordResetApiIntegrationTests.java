package com.micon.backend.auth;

import com.micon.backend.domain.auth.mail.PasswordResetCodeSender;
import com.micon.backend.user.domain.User;
import com.micon.backend.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:password-reset;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@Transactional
class PasswordResetApiIntegrationTests {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @MockitoBean PasswordResetCodeSender codeSender;

    @Test
    void resetsPasswordOnceAndRevokesExistingSessions() throws Exception {
        SessionTokens session = createUserAndLogin("reset@micon.com", "OldPassword123!");
        ResetChallenge challenge = issue("reset@micon.com");
        String resetToken = verifyCode(challenge.requestId(), challenge.code());
        mockMvc.perform(post("/auth/password-reset-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + challenge.requestId() + "\",\"code\":\"" + challenge.code() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_CODE"));

        mockMvc.perform(post("/auth/password-resets")
                        .header("X-Password-Reset-Token", resetToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isOk());

        User changed = userRepository.findByEmail("reset@micon.com").orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword123!", changed.getPassword())).isTrue();

        login("reset@micon.com", "OldPassword123!").andExpect(status().isUnauthorized());
        login("reset@micon.com", "NewPassword123!").andExpect(status().isOk());
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/password-resets")
                        .header("X-Password-Reset-Token", resetToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"AnotherPassword123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));
    }

    @Test
    void rejectsExpiredCode() throws Exception {
        userRepository.save(new User("expired@micon.com", passwordEncoder.encode("Password123!")));
        ResetChallenge challenge = issue("expired@micon.com");
        entityManager.flush();
        jdbcTemplate.update("UPDATE password_reset_request SET expires_at = '2000-01-01 00:00:00' WHERE request_id = ?",
                challenge.requestId());
        entityManager.clear();

        mockMvc.perform(post("/auth/password-reset-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + challenge.requestId() + "\",\"code\":\"" + challenge.code() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_CODE"));
    }

    @Test
    void locksChallengeAfterMaximumFailedAttempts() throws Exception {
        userRepository.save(new User("attempts@micon.com", passwordEncoder.encode("Password123!")));
        ResetChallenge challenge = issue("attempts@micon.com");

        String wrongCode = challenge.code().equals("000000") ? "999999" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            verifyCodeExpectingFailure(challenge.requestId(), wrongCode);
        }
        mockMvc.perform(post("/auth/password-reset-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + challenge.requestId() + "\",\"code\":\"" + challenge.code() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_CODE"));
    }

    @Test
    void doesNotRevealWhetherEmailExists() throws Exception {
        mockMvc.perform(post("/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@micon.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        verify(codeSender, never()).send(anyString(), anyString());
    }

    private ResetChallenge issue(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(codeSender).send(org.mockito.ArgumentMatchers.eq(email), code.capture());
        return new ResetChallenge(field(result, "requestId"), code.getValue());
    }

    private String verifyCode(String requestId, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/password-reset-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return field(result, "resetToken");
    }

    private void verifyCodeExpectingFailure(String requestId, String code) throws Exception {
        mockMvc.perform(post("/auth/password-reset-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private SessionTokens createUserAndLogin(String email, String password) throws Exception {
        userRepository.save(new User(email, passwordEncoder.encode(password)));
        MvcResult result = login(email, password).andExpect(status().isOk()).andReturn();
        return new SessionTokens(field(result, "accessToken"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private String field(MvcResult result, String name) throws Exception {
        Matcher matcher = Pattern.compile("\\\"" + name + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private record ResetChallenge(String requestId, String code) {}
    private record SessionTokens(String accessToken) {}
}
