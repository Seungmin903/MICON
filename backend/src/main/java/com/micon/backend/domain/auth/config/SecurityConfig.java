package com.micon.backend.domain.auth.config;

import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.auth.token.ActiveSessionValidator;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, GoogleAuthProperties.class, PasswordResetProperties.class})
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/v1/swagger-ui.html", "/v1/swagger-ui/**",
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/policies").permitAll()
                        .requestMatchers(HttpMethod.GET, "/homepage", "/homepage/**",
                                "/v1/homepage", "/v1/homepage/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/songs", "/songs/*").permitAll()
                        // 장르 목록은 계정과 무관한 카탈로그라 곡 목록처럼 비로그인도 받는다(온보딩·연습 탭 장르 칩)
                        .requestMatchers(HttpMethod.GET, "/genres").permitAll()
						.requestMatchers(HttpMethod.GET, "/artist-portraits/min-kyung-hoon.jpg").permitAll()
					.requestMatchers(HttpMethod.GET, "/songs/*/practice-assets", "/songs/*/practice-guide",
						"/songs/*/mr").permitAll()
                        .requestMatchers(HttpMethod.POST, "/callbacks/voice-analyses",
                                "/callbacks/voice-analysis-components").permitAll()
                        .requestMatchers("/voice-analysis-sessions", "/voice-analysis-sessions/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/embedding-catalog/active").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/catalog-media/vocal-stems/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/google", "/auth/refresh",
                                "/auth/password-reset-requests", "/auth/password-reset-verifications",
                                "/auth/password-resets").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(request -> {
                            if ("/auth/refresh".equals(request.getRequestURI())
                                    || "/callbacks/voice-analyses".equals(request.getRequestURI())
                                    || "/callbacks/voice-analysis-components".equals(request.getRequestURI())
                                    || "/internal/embedding-catalog/active".equals(request.getRequestURI())
                                    || request.getRequestURI().startsWith("/internal/catalog-media/vocal-stems/")) {
                                return null;
                            }
                            return new DefaultBearerTokenResolver().resolve(request);
                        })
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, ErrorCode.ACCESS_DENIED)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, ErrorCode.ACCESS_DENIED)));
        return http.build();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        SecretKey key = secretKey(properties);
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties, ActiveSessionValidator activeSessionValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                activeSessionValidator
        ));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    private SecretKey secretKey(AuthProperties properties) {
        return new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + errorCode.getCode()
                + "\",\"message\":\"" + errorCode.getMessage() + "\",\"data\":null}");
    }
}
