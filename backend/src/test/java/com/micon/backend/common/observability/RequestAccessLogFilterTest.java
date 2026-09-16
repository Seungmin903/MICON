package com.micon.backend.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestAccessLogFilterTest {
    @Test
    void correlatesRequestAndResponseWithoutLoggingCredentialsOrPersonalPaths() throws Exception {
        try (var logs = new AccessLogCapture()) {
            String trace = UUID.randomUUID().toString();
            var request = new MockHttpServletRequest("GET", "/songs/private-value");
            request.addHeader("X-Request-ID", trace);
            request.addHeader("Authorization", "Bearer private-token");
            request.setQueryString("password=private-secret");
            var response = new MockHttpServletResponse();
            MDC.put("existing", "preserve");
            new RequestAccessLogFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get(AccessLog.TRACE_ID)).isEqualTo(trace);
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/songs/{songId}");
                response.setStatus(409);
            });
            assertThat(response.getHeader("X-Request-ID")).isEqualTo(trace);
            assertThat(logs.text()).contains("event=http_access", "trace_id=" + trace,
                    "route=/songs/{songId}", "status=409").doesNotContain("private-value", "private-token", "private-secret");
            assertThat(MDC.get(AccessLog.TRACE_ID)).isNull();
            assertThat(MDC.get("existing")).isEqualTo("preserve");
        }
    }

    @Test
    void rejectsUntrustedTraceIdAndCleansUpAfterFailure() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var request = new MockHttpServletRequest("GET", "/private-unknown-path");
            request.addHeader("X-Request-ID", "secret\r\nevent=forged");
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> new RequestAccessLogFilter().doFilter(request, response,
                    (req, res) -> { throw new IOException("private exception message"); }))
                    .isInstanceOf(IOException.class);
            assertThat(UUID.fromString(response.getHeader("X-Request-ID"))).isNotNull();
            assertThat(logs.text()).contains("route=unmatched", "status=500", "error=IOException")
                    .doesNotContain("forged", "secret", "private");
            assertThat(MDC.get(AccessLog.TRACE_ID)).isNull();
        }
    }
}
